package com.tinyclaw;

import com.tinyclaw.config.AppConfig;
import com.tinyclaw.config.ConfigLoader;
import com.tinyclaw.config.EngineConfig;
import com.tinyclaw.config.ProviderConfig;
import com.tinyclaw.engine.AgentEngine;
import com.tinyclaw.model.ToolCall;
import com.tinyclaw.model.ToolDefinition;
import com.tinyclaw.model.ToolResult;
import com.tinyclaw.provider.ClaudeProvider;
import com.tinyclaw.provider.LLMProvider;
import com.tinyclaw.provider.OpenAICompatProvider;
import com.tinyclaw.provider.ProviderException;
import com.tinyclaw.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

public class ClawApplication {

    private static final Logger log = LoggerFactory.getLogger(ClawApplication.class);

    // ==========================================
    // 临时的 Tool Registry（真实工具将在第 05 讲实现）
    // ==========================================

    /**
     * 模拟工具注册表：注册了 bash 和 get_weather 工具，返回伪造的执行结果。
     * <p>
     * 用于在真实 Provider 接入后验证两阶段引擎的完整链路。
     * 真实 Tool Registry 将在后续章节实现后替换。
     */
    static class MockRegistry implements ToolRegistry {

        /** 模拟的城市天气数据 */
        private static final Map<String, String> WEATHER_DATA = Map.of(
                "北京", "API 返回：今天是晴天，气温 25 度。",
                "上海", "API 返回：今天多云转阴，气温 22 度。"
        );

        @Override
        public List<ToolDefinition> getAvailableTools() {
            return List.of(
                    new ToolDefinition(
                            "bash",
                            "Execute a shell command in the workspace",
                            Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                            "command", Map.of("type", "string", "description", "The command to execute")
                                    ),
                                    "required", List.of("command")
                            )
                    ),
                    new ToolDefinition(
                            "get_weather",
                            "获取指定城市的当前天气情况。",
                            Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                            "city", Map.of("type", "string", "description", "城市名称，如 北京、上海")
                                    ),
                                    "required", List.of("city")
                            )
                    )
            );
        }

        @Override
        public ToolResult execute(ToolCall call) {
            if ("bash".equals(call.name())) {
                return ToolResult.success(
                        call.id(),
                        "-rw-r--r-- 1 user group 234 Oct 24 10:00 main.go\n"
                );
            }
            if ("get_weather".equals(call.name())) {
                String city = call.arguments().path("city").asText("未知");
                log.info(" -> [Mock 工具执行] 获取 {} 的天气中...", city);
                String weatherResult = WEATHER_DATA.getOrDefault(city, "API 返回：暂无该城市数据");
                return ToolResult.success(call.id(), weatherResult);
            }
            return ToolResult.error(call.id(), "未知工具: " + call.name());
        }
    }

    // ==========================================
    // 组装运行
    // ==========================================

    public static void main(String[] args) {
        AppConfig config = ConfigLoader.load();
        ProviderConfig providerConfig = config.provider();
        EngineConfig engineConfig = config.engine();

        MockRegistry r = new MockRegistry();

        LLMProvider p = createProvider(providerConfig);

        String workDir = engineConfig.workDir();
        if (workDir == null || workDir.isEmpty() || ".".equals(workDir)) {
            workDir = System.getProperty("user.dir");
        }
        AgentEngine eng = new AgentEngine(p, r, workDir, engineConfig.enableThinking());

        try {
            eng.run("我想去北京跑步，帮我查查天气适合吗？");
        } catch (ProviderException e) {
            log.error("引擎运行崩溃: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    /**
     * 根据配置文件创建对应的 Provider 实现。
     *
     * @param config Provider 配置
     * @return Provider 实例
     */
    private static LLMProvider createProvider(ProviderConfig config) {
        log.info("已接入 Provider [{}]: {} @ {}", config.type(), config.model(), config.baseUrl());
        return switch (config.type()) {
            case "claude" -> new ClaudeProvider(config.model(), config.apiKey(), config.baseUrl());
            case "openai" -> new OpenAICompatProvider(config.model(), config.apiKey(), config.baseUrl());
            default -> {
                log.warn("未知的 provider.type: {}，使用 OpenAICompatProvider", config.type());
                yield new OpenAICompatProvider(config.model(), config.apiKey(), config.baseUrl());
            }
        };
    }
}
