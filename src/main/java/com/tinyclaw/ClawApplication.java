package com.tinyclaw;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinyclaw.engine.AgentEngine;
import com.tinyclaw.model.Message;
import com.tinyclaw.model.Role;
import com.tinyclaw.model.ToolCall;
import com.tinyclaw.model.ToolDefinition;
import com.tinyclaw.model.ToolResult;
import com.tinyclaw.provider.LLMProvider;
import com.tinyclaw.tools.ToolRegistry;

import java.util.List;
import java.util.Map;

public class ClawApplication {

    /** JSON 解析器，用于构造 Mock 工具参数 */
    private static final ObjectMapper mapper = new ObjectMapper();

    // ==========================================
    // 1. 升级版 Mock Provider：支持两阶段
    // ==========================================

    /**
     * 模拟大模型响应：根据传入的工具列表是否为空区分 Phase 1（慢思考）和 Phase 2（行动）。
     */
    static class MockProvider implements LLMProvider {
        /** 当前轮次计数器，仅在 Phase 2 时递增 */
        private int turn = 0;

        @Override
        public Message generate(List<Message> messages, List<ToolDefinition> availableTools) {
            // 如果工具列表为空，说明这是引擎发起的 Phase 1: Thinking 阶段
            if (availableTools.isEmpty()) {
                return new Message(
                        Role.ASSISTANT,
                        "【推理中】目标是检查文件。我不能直接盲猜，我需要先调用 bash 工具执行 ls 命令，看看当前目录下有什么，然后再做定夺。"
                );
            }

            // 如果工具列表不为空，说明这是 Phase 2: Action 阶段
            turn++;
            if (turn == 1) {
                // 第一轮 Action：顺着刚才的 Thinking，精准调用工具
                JsonNode args;
                try {
                    // 将 JSON 字符串解析成树形结构的 JsonNode 对象
                    args = mapper.readTree("{\"command\": \"ls -la\"}");
                } catch (JsonProcessingException e) {
                    throw new RuntimeException(e);
                }
                return new Message(
                        Role.ASSISTANT,
                        "我要执行我刚才计划的步骤了。",
                        List.of(new ToolCall("call_123", "bash", args))
                );
            }

            // 第二轮 Action：直接总结退出
            return new Message(
                    Role.ASSISTANT,
                    "根据工具返回的结果，我看到了 main.go，任务圆满完成！"
            );
        }
    }

    // ==========================================
    // 2. Mock Tool Registry
    // ==========================================

    /**
     * 模拟工具注册表：注册了 bash 工具并返回伪造的终端输出。
     */
    static class MockRegistry implements ToolRegistry {
        @Override
        public List<ToolDefinition> getAvailableTools() {
            return List.of(new ToolDefinition(
                    "bash",
                    "Execute a shell command in the workspace",
                    Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "command", Map.of("type", "string", "description", "The command to execute")
                            ),
                            "required", List.of("command")
                    )
            ));
        }

        @Override
        public ToolResult execute(ToolCall call) {
            return ToolResult.success(
                    call.id(),
                    "-rw-r--r-- 1 user group 234 Oct 24 10:00 main.go\n"
            );
        }
    }

    // ==========================================
    // 3. 组装运行
    // ==========================================

    public static void main(String[] args) {
        // 获取当前执行目录作为 WorkDir 物理边界
        String workDir = System.getProperty("user.dir");

        MockProvider p = new MockProvider();
        MockRegistry r = new MockRegistry();

        // 实例化核心引擎，开启 EnableThinking = true
        AgentEngine eng = new AgentEngine(p, r, workDir, true);

        // 发起任务指令
        eng.run("帮我检查当前目录的文件");
    }
}
