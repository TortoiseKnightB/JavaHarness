package com.tinyclaw;

import com.tinyclaw.config.AppConfig;
import com.tinyclaw.config.ConfigLoader;
import com.tinyclaw.config.EngineConfig;
import com.tinyclaw.config.ProviderConfig;
import com.tinyclaw.config.ServerConfig;
import com.tinyclaw.engine.AgentEngine;
import com.tinyclaw.engine.ConsoleReporter;
import com.tinyclaw.feishu.FeishuBot;
import com.tinyclaw.config.FeishuConfig;
import com.tinyclaw.provider.ClaudeProvider;
import com.tinyclaw.provider.LLMProvider;
import com.tinyclaw.provider.OpenAICompatProvider;
import com.tinyclaw.provider.ProviderException;
import com.tinyclaw.tools.ToolRegistry;
import com.tinyclaw.tools.ToolRegistryImpl;
import com.tinyclaw.tools.builtin.BashTool;
import com.tinyclaw.tools.builtin.EditFileTool;
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
        ServerConfig serverConfig = config.server();

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
        r.register(new EditFileTool(workDir));

        // 4. 实例化核心引擎
        AgentEngine eng = new AgentEngine(p, r, workDir, engineConfig.enableThinking());

        // 5. 根据配置的模式启动
        String mode = serverConfig.mode();
        if ("feishu".equals(mode)) {
            startFeishuMode(eng, config.feishu());
        } else {
            startCliMode(eng);
        }
    }

    /**
     * CLI 模式：从命令行执行一次任务。
     */
    private static void startCliMode(AgentEngine eng) {
        log.info("启动模式: CLI");
        try {
            eng.run("我需要在当前目录下新建一个 ping.java，提供一个简单的 http ping 接口。 写完之后，帮我把代码用 git 提交一下。",
                    new ConsoleReporter());
        } catch (ProviderException e) {
            log.error("引擎运行崩溃: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    /**
     * 飞书模式：建立 WebSocket 长连接，挂起主线程等待消息。
     */
    private static void startFeishuMode(AgentEngine eng, FeishuConfig feishuConfig) {
        log.info("启动模式: Feishu (WebSocket 长连接)");
        FeishuBot bot = new FeishuBot(eng, feishuConfig);
        bot.start();  // 阻塞直到连接断开
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
