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
 * 只负责维护上下文时间线，严格执行 ReAct 范式：
 * 把模型的意图（ToolCall）交给执行层，再把物理世界的反馈（Observation）追加回内存。
 */
public class AgentEngine {

    private static final Logger log = LoggerFactory.getLogger(AgentEngine.class);

    /**
     * 默认的系统提示词，在 ContextManager 接入前占位使用
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
     * @param provider 大模型适配器
     * @param registry 工具注册表
     * @param workDir  工作区物理边界目录
     */
    public AgentEngine(LLMProvider provider, ToolRegistry registry, String workDir) {
        this.provider = provider;
        this.registry = registry;
        this.workDir = workDir;
    }

    /**
     * 启动 Agent 的生命周期，执行 ReAct 循环直到任务完成。
     *
     * @param userPrompt 用户输入的任务描述
     */
    public void run(String userPrompt) {
        log.info("[Engine] 引擎启动，锁定工作区: {}", workDir);

        // 1. 初始化会话的 Context (上下文内存)
        //    在真实的场景中，这里会由 PromptComposer 动态加载 AGENTS.md。目前先硬编码。
        List<Message> contextHistory = new ArrayList<>();
        contextHistory.add(new Message(Role.SYSTEM, DEFAULT_SYSTEM_PROMPT));
        contextHistory.add(new Message(Role.USER, userPrompt));

        int turnCount = 0;

        // 2. The Main Loop: 心跳开始 (标准的 ReAct 循环)
        while (true) {
            turnCount++;
            log.info("========== [Turn {}] 开始 ==========", turnCount);

            // 获取当前挂载的所有工具定义
            List<ToolDefinition> availableTools = registry.getAvailableTools();

            // 向大模型发起推理请求 (包含 Reasoning)
            log.info("[Engine] 正在思考 (Reasoning)...");
            Message responseMsg = provider.generate(contextHistory, availableTools);

            // 将模型的响应完整追加到上下文历史中
            contextHistory.add(responseMsg);

            // 如果模型回复了纯文本，打印出来（这通常是它的思考过程，或是最终结果）
            if (responseMsg.content() != null && !responseMsg.content().isEmpty()) {
                System.out.println("🤖 模型: " + responseMsg.content());
            }

            // 3. 退出条件判断：无工具调用 → 任务完成
            if (!responseMsg.hasToolCalls()) {
                log.info("[Engine] 任务完成，退出循环。");
                break;
            }

            // 4. 执行行动 (Action) 与 获取观察结果 (Observation)
            log.info("[Engine] 模型请求调用 {} 个工具...", responseMsg.toolCalls().size());

            for (ToolCall toolCall : responseMsg.toolCalls()) {
                log.info(" -> 🛠️ 执行工具: {}, 参数: {}", toolCall.name(), toolCall.arguments());

                // 通过 Registry 路由并执行底层工具
                ToolResult result = registry.execute(toolCall);

                if (result.isError()) {
                    log.info(" -> ❌ 工具执行报错: {}", result.output());
                } else {
                    log.info(" -> ✅ 工具执行成功 (返回 {} 字节)", result.output().length());
                }

                // 将 Observation 封装为 User Message 追加到上下文
                // 注意：ToolCallID 必须携带！这是维系大模型推理链条的关键
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
