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
    // 1. 伪造的大模型 Provider
    // ==========================================

    /**
     * 模拟大模型的响应：第一轮请求执行 bash，第二轮输出最终结果。
     * 用于在真实 Provider 实现完成前验证 Main Loop 的健壮性。
     */
    static class MockProvider implements LLMProvider {
        /** 当前轮次计数器，模拟模型的多轮决策 */
        private int turn = 0;

        @Override
        public Message generate(List<Message> messages, List<ToolDefinition> availableTools) {
            turn++;
            if (turn == 1) {
                // 第一轮：请求执行 bash ls -la
                JsonNode args;
                try {
                    // 将 JSON 字符串解析成树形结构的 JsonNode 对象
                    args = mapper.readTree("{\"command\": \"ls -la\"}");
                } catch (JsonProcessingException e) {
                    throw new RuntimeException(e);
                }
                return new Message(
                        Role.ASSISTANT,
                        "让我来看看当前目录下有什么文件。",
                        List.of(new ToolCall("call_123", "bash", args))
                );
            }
            // 第二轮：输出最终结果，不再请求工具
            return new Message(
                    Role.ASSISTANT,
                    "我看到了文件列表，里面包含 main.go，任务完成！"
            );
        }
    }

    // ==========================================
    // 2. 伪造的 Tool Registry
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
            // 直接返回一段伪造的终端输出
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

        // 实例化核心引擎
        AgentEngine eng = new AgentEngine(p, r, workDir);

        // 发起任务指令
        eng.run("帮我检查当前目录的文件");
    }
}
