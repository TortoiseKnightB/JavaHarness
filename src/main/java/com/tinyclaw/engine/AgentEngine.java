package com.tinyclaw.engine;

import com.tinyclaw.context.Compactor;
import com.tinyclaw.context.PromptComposer;
import com.tinyclaw.model.Message;
import com.tinyclaw.model.Role;
import com.tinyclaw.model.ToolCall;
import com.tinyclaw.model.ToolDefinition;
import com.tinyclaw.model.ToolResult;
import com.tinyclaw.provider.LLMProvider;
import com.tinyclaw.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * AgentEngine 是微型 OS 的核心驱动。
 * <p>
 * 只负责维护上下文时间线，严格执行 Two-Stage ReAct 范式：
 * Phase 1 剥夺工具强制规划，Phase 2 恢复工具精准执行。
 * 工具分发采用 Fork-Join 并发模式：预分配数组 + CompletableFuture + 虚拟线程，无锁线程安全且结果天然保序。
 * <p>
 * 引擎本身无状态——WorkDir 跟随 Session 走，所有上下文持久化由 Session 负责。
 */
public class AgentEngine {

    private static final Logger log = LoggerFactory.getLogger(AgentEngine.class);

    /**
     * 虚拟线程执行器，用于并行工具调用的 Fork-Join 分发。
     * <p>
     * 虚拟线程极轻量，I/O 密集型操作可瞬间启动数千线程无压力。
     */
    private static final Executor VIRTUAL_THREADS = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 从 Session 中提取的上下文窗口大小（给压缩器充足的判断空间）
     */
    private static final int WORKING_MEMORY_LIMIT = 20;

    /**
     * Compactor 保护区大小：最近 N 条消息免于全量掩码，仅做 Head-Tail 截断
     */
    private static final int COMPACTOR_RETAIN_MSGS = 6;

    /**
     * 上下文压缩器水位线（字符数），便于测试设为积极阈值
     */
    private static final int COMPACTOR_MAX_CHARS = 3000;

    /**
     * 大模型适配器：负责与底层模型 API 通信
     */
    private final LLMProvider provider;

    /**
     * 工具注册表：管理与分发所有可用工具
     */
    private final ToolRegistry registry;

    /**
     * 慢思考模式开关：复杂任务打开以强制两阶段规划，简单任务关闭以节省 Token
     */
    private final boolean enableThinking;

    /**
     * 上下文压缩器：防止大模型发生 Context Window OOM
     */
    private final Compactor compactor;

    /**
     * @param provider       大模型适配器
     * @param registry       工具注册表
     * @param enableThinking 是否开启慢思考模式（Two-Stage ReAct）
     */
    public AgentEngine(LLMProvider provider, ToolRegistry registry, boolean enableThinking) {
        this.provider = provider;
        this.registry = registry;
        this.enableThinking = enableThinking;
        this.compactor = new Compactor(COMPACTOR_MAX_CHARS, COMPACTOR_RETAIN_MSGS);
    }

    /**
     * 从 Session 中恢复记忆，执行 ReAct 循环直到本轮任务完成。
     * <p>
     * 调用方需先将用户消息通过 {@link Session#append(Message...)} 追加到 Session 中，
     * 再调用本方法。引擎从 Session 的工作区中动态组装 System Prompt，从 Session 中提取
     * Working Memory 作为上下文历史。
     *
     * @param session  会话实例，承载完整历史与工作区绑定
     * @param reporter 输出报告器，为 null 时退化为 ConsoleReporter
     */
    public void run(Session session, Reporter reporter) {
        final Reporter rep = reporter != null ? reporter : new ConsoleReporter();
        log.info("[Engine] 唤醒会话 [{}]，锁定工作区: {}", session.id(), session.workDir());
        log.info("[Engine] 慢思考模式 (Thinking Phase): {}", enableThinking);

        while (true) {
            // 获取当前挂载的所有工具定义
            List<ToolDefinition> availableTools = registry.getAvailableTools();

            // 1. 【上下文组装】: System Prompt + 截取最近的 N 条消息作为 Working Memory
            PromptComposer composer = new PromptComposer(session.workDir());
            Message systemMsg = composer.build();

            List<Message> workingMemory = session.getWorkingMemory(WORKING_MEMORY_LIMIT);

            List<Message> contextHistory = new ArrayList<>();
            contextHistory.add(systemMsg);
            contextHistory.addAll(workingMemory);

            // 2. 【核心注入点】: 在向 Provider 发起推理前，过一遍内存压缩器
            // 无论带出了多少上下文，如果字符总数超标，早期日志将被掩码化，超大日志将被掐头去尾
            List<Message> compactedContext = compactor.compact(contextHistory);

            // ====================================================================
            // Phase 1: 慢思考阶段 (Thinking) - 剥夺工具，强制规划
            // ====================================================================
            if (enableThinking) {
                log.info("[Engine][Phase 1] 剥夺工具访问权，强制进入慢思考与规划阶段...");
                rep.onThinking();

                Message thinkResp = provider.generate(compactedContext, List.of());

                if (thinkResp.content() != null && !thinkResp.content().isEmpty()) {
                    rep.onMessage("🧠 [内部思考 Trace]: " + thinkResp.content());
                    compactedContext = new ArrayList<>(compactedContext);
                    compactedContext.add(thinkResp);
                    // 写入 Session 的是全量真实响应，不受 Compact 影响
                    session.append(thinkResp);
                }
            }

            // ====================================================================
            // Phase 2: 行动阶段 (Action) - 恢复工具，顺着规划执行
            // ====================================================================
            log.info("[Engine][Phase 2] 恢复工具挂载，等待模型采取行动...");

            Message actionResp = provider.generate(compactedContext, availableTools);
            compactedContext = new ArrayList<>(compactedContext);
            compactedContext.add(actionResp);
            session.append(actionResp);

            // 如果模型回复了纯文本，通过 Reporter 输出（对外回复）
            if (actionResp.content() != null && !actionResp.content().isEmpty()) {
                rep.onMessage(actionResp.content());
            }

            // ====================================================================
            // 退出与执行逻辑
            // ====================================================================

            // 3. 退出条件判断：无工具调用 → 本轮任务完成，挂起等待人类下一条指令
            if (!actionResp.hasToolCalls()) {
                log.info("[Engine] 模型未请求调用工具，本轮任务完成。");
                break;
            }

            // 4. Fork-Join 并发执行工具 (Fork-Join 模式)
            List<ToolCall> toolCalls = actionResp.toolCalls();
            log.info("[Engine] 模型请求并发调用 {} 个工具...", toolCalls.size());

            // 预分配固定长度的数组，每个协程通过专属索引并发写入，无需加锁
            Message[] observationMsgs = new Message[toolCalls.size()];
            CompletableFuture<?>[] futures = new CompletableFuture[toolCalls.size()];

            for (int i = 0; i < toolCalls.size(); i++) {
                final int idx = i;
                final ToolCall call = toolCalls.get(i);
                futures[idx] = CompletableFuture.runAsync(() -> {
                    rep.onToolCall(call.name(), call.arguments().toString());

                    ToolResult result = registry.execute(call);

                    rep.onToolResult(call.name(), result.output(), result.isError());

                    // 线程安全：每个虚拟线程操作预分配数组的不同索引，无需加锁
                    observationMsgs[idx] = new Message(
                            Role.USER,
                            result.output(),
                            List.of(),
                            call.id()
                    );
                }, VIRTUAL_THREADS);
            }

            // Join 阻塞等待：主循环挂起，直到所有并发虚拟线程全部执行完毕
            CompletableFuture.allOf(futures).join();

            log.info("[Engine] 所有并发工具执行完毕，开始聚合观察结果 (Observation)...");

            // 持久化到 Session，开启下一轮的复盘与推理（全量原始数据，不受压缩影响）
            session.append(observationMsgs);

            // 按原始顺序追加到临时上下文时间线
            for (Message obs : observationMsgs) {
                compactedContext.add(obs);
            }

            // 循环回到开头，模型将带着新加入的 Observation 继续它的下一轮思考...
        }
    }
}
