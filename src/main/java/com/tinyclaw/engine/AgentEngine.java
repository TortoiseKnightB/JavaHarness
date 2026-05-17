package com.tinyclaw.engine;

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

/**
 * AgentEngine 是微型 OS 的核心驱动。
 * <p>
 * 只负责维护上下文时间线，严格执行 Two-Stage ReAct 范式：
 * Phase 1 剥夺工具强制规划，Phase 2 恢复工具精准执行。
 */
public class AgentEngine {

    private static final Logger log = LoggerFactory.getLogger(AgentEngine.class);

    /**
     * 默认的系统提示词，在 PromptComposer 接入前占位使用
     */
    private static final String DEFAULT_SYSTEM_PROMPT =
            "You are java-tiny-claw, an expert coding assistant. You have full access to tools in the workspace.";

    /**
     * 大模型适配器：负责与底层模型 API 通信
     */
    private final LLMProvider provider;

    /**
     * 工具注册表：管理与分发所有可用工具
     */
    private final ToolRegistry registry;

    /**
     * 工作区物理边界：借鉴 OpenClaw 理念，
     * Agent 必须像普通开发者一样受限于某个具体的项目目录。
     */
    private final String workDir;

    /**
     * 慢思考模式开关：复杂任务打开以强制两阶段规划，简单任务关闭以节省 Token
     */
    private final boolean enableThinking;

    /**
     * @param provider       大模型适配器
     * @param registry       工具注册表
     * @param workDir        工作区物理边界目录
     * @param enableThinking 是否开启慢思考模式（Two-Stage ReAct）
     */
    public AgentEngine(LLMProvider provider, ToolRegistry registry, String workDir, boolean enableThinking) {
        this.provider = provider;
        this.registry = registry;
        this.workDir = workDir;
        this.enableThinking = enableThinking;
    }

    /**
     * 启动 Agent 的生命周期，执行 Two-Stage ReAct 循环直到任务完成。
     *
     * @param userPrompt 用户输入的任务描述
     */
    public void run(String userPrompt) {
        log.info("[Engine] 引擎启动，锁定工作区: {}", workDir);
        log.info("[Engine] 慢思考模式 (Thinking Phase): {}", enableThinking);

        // 1. 初始化会话的 Context (上下文内存)
        List<Message> contextHistory = new ArrayList<>();
        contextHistory.add(new Message(Role.SYSTEM, DEFAULT_SYSTEM_PROMPT));
        contextHistory.add(new Message(Role.USER, userPrompt));

        int turnCount = 0;

        // 2. The Main Loop: 心跳开始 (Two-Stage ReAct 循环)
        while (true) {
            turnCount++;
            log.info("\n========== [Turn {}] 开始 ==========", turnCount);

            // 获取当前挂载的所有工具定义
            List<ToolDefinition> availableTools = registry.getAvailableTools();

            // ====================================================================
            // Phase 1: 慢思考阶段 (Thinking) - 剥夺工具，强制规划
            // ====================================================================
            if (enableThinking) {
                log.info("[Engine][Phase 1] 剥夺工具访问权，强制进入慢思考与规划阶段...");

                // 核心机制：传入空的工具列表！
                // 大模型看不到任何 JSON Schema，被迫只能输出纯文本的思考过程。
                Message thinkResp = provider.generate(contextHistory, List.of());

                // 如果模型输出了思考过程，将其作为 Assistant 消息追加到上下文中
                if (thinkResp.content() != null && !thinkResp.content().isEmpty()) {
                    System.out.println("🧠 [内部思考 Trace]: " + thinkResp.content());
                    contextHistory.add(thinkResp);
                }
            }

            // ====================================================================
            // Phase 2: 行动阶段 (Action) - 恢复工具，顺着规划执行
            // ====================================================================
            log.info("[Engine][Phase 2] 恢复工具挂载，等待模型采取行动...");

            // 此时的 contextHistory 中已包含 Thinking Trace。
            // 模型会顺着自己的逻辑，结合恢复的 availableTools 发起精准的工具调用。
            Message actionResp = provider.generate(contextHistory, availableTools);
            contextHistory.add(actionResp);

            // 如果模型回复了纯文本，打印出来（对外回复）
            if (actionResp.content() != null && !actionResp.content().isEmpty()) {
                System.out.println("🤖 [对外回复]: " + actionResp.content());
            }

            // ====================================================================
            // 退出与执行逻辑
            // ====================================================================

            // 3. 退出条件判断：无工具调用 → 任务完成
            if (!actionResp.hasToolCalls()) {
                log.info("[Engine] 模型未请求调用工具，任务宣告完成。");
                break;
            }

            // 4. 执行行动 (Action) 与 获取观察结果 (Observation)
            log.info("[Engine] 模型请求调用 {} 个工具...", actionResp.toolCalls().size());

            for (ToolCall toolCall : actionResp.toolCalls()) {
                log.info(" -> 🛠️ 执行工具: {}, 参数: {}", toolCall.name(), toolCall.arguments());

                // 通过 Registry 路由并执行底层工具
                ToolResult result = registry.execute(toolCall);

                if (result.isError()) {
                    log.info(" -> ❌ 工具执行报错: {}", result.output());
                } else {
                    log.info(" -> ✅ 工具执行成功 (返回 {} 字节)", result.output().length());
                }

                // 将工具的 Observation 追加到 Context，准备进入下一轮
                Message observationMsg = new Message(
                        Role.USER,
                        result.output(),
                        List.of(),
                        toolCall.id()
                );
                contextHistory.add(observationMsg);
            }

            // 循环回到开头，模型将带着新加入的 Observation 继续它的下一轮思考...
        }
    }
}
