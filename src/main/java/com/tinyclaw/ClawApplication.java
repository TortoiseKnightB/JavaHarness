package com.tinyclaw;

import com.tinyclaw.config.AppConfig;
import com.tinyclaw.config.ConfigLoader;
import com.tinyclaw.config.EngineConfig;
import com.tinyclaw.config.ProviderConfig;
import com.tinyclaw.engine.AgentEngine;
import com.tinyclaw.provider.ClaudeProvider;
import com.tinyclaw.provider.LLMProvider;
import com.tinyclaw.provider.OpenAICompatProvider;
import com.tinyclaw.provider.ProviderException;
import com.tinyclaw.tools.ToolRegistry;
import com.tinyclaw.tools.ToolRegistryImpl;
import com.tinyclaw.tools.builtin.BashTool;
import com.tinyclaw.tools.builtin.ReadFileTool;
import com.tinyclaw.tools.builtin.WriteFileTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClawApplication {

    private static final Logger log = LoggerFactory.getLogger(ClawApplication.class);

    public static void main(String[] args) {
        AppConfig config = ConfigLoader.load();
        ProviderConfig providerConfig = config.provider();
        EngineConfig engineConfig = config.engine();

        // 1. 获取工作区物理边界
        String workDir = engineConfig.workDir();
        if (workDir == null || workDir.isEmpty() || ".".equals(workDir)) {
            workDir = System.getProperty("user.dir");
        }

        // 2. 初始化真实的大脑
        LLMProvider p = createProvider(providerConfig);

        // 3. 初始化真实的 Tool Registry，挂载极简工具集
        ToolRegistry r = new ToolRegistryImpl();
        r.register(new ReadFileTool(workDir));
        r.register(new WriteFileTool(workDir));
        r.register(new BashTool(workDir));

        // 4. 实例化核心引擎（简单任务关闭 Thinking 以极速响应）
        AgentEngine eng = new AgentEngine(p, r, workDir, engineConfig.enableThinking());

        // 5. 多工具组合测试：检查 Java 版本 → 创建 HelloWorld.java → 编译运行
        try {
            eng.run("请依次完成以下操作：1. 用 bash 查看当前系统的 python 版本 2. 创建一个 HelloWorld.py 输出 Hello, java-tiny-claw! 3. 用 bash 编译并运行这个文件，向我展示结果。");
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
