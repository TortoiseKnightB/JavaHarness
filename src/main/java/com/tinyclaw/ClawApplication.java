package com.tinyclaw;

import com.tinyclaw.config.AppConfig;
import com.tinyclaw.config.ConfigLoader;
import com.tinyclaw.config.EngineConfig;
import com.tinyclaw.config.FeishuConfig;
import com.tinyclaw.config.ProviderConfig;
import com.tinyclaw.config.ServerConfig;
import com.tinyclaw.engine.AgentEngine;
import com.tinyclaw.engine.ConsoleReporter;
import com.tinyclaw.engine.Session;
import com.tinyclaw.engine.Session.SessionManager;
import com.tinyclaw.feishu.FeishuBot;
import com.tinyclaw.model.Message;
import com.tinyclaw.model.Role;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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

        // 4. 实例化核心引擎（无状态，WorkDir 跟随 Session）
        AgentEngine eng = new AgentEngine(p, r, engineConfig.enableThinking(), engineConfig.planMode());

        // 5. 根据配置的模式启动
        String mode = serverConfig.mode();
        if ("feishu".equals(mode)) {
            startFeishuMode(eng, config.feishu(), workDir);
        }  else if ("doomloop".equals(mode)) {
            startDoomLoopTest(eng, workDir);
        } else {
            startCliMode(eng, workDir);
        }
    }

    /**
     * CLI 模式：从命令行执行一次任务。
     */
    private static void startCliMode(AgentEngine eng, String workDir) {
        log.info("启动模式: CLI");
        try {
            Session session = new Session("cli", workDir);
            session.append(new Message(Role.USER,
                    "我需要在当前目录下新建一个 ping.java，提供一个简单的 http ping 接口。 写完之后，帮我把代码用 git 提交一下。"));
            eng.run(session, new ConsoleReporter());
        } catch (ProviderException e) {
            log.error("引擎运行崩溃: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    /**
     * Doom Loop 测试：逼迫 Agent 陷入死胡同，验证 ReminderInjector 触发死循环干预。
     * <p>
     * 让 Agent 读取一个不存在的 secret_key.txt，并用误导性指令要求它"不要改变参数直接重试"。
     * 前 3 次 Agent 会连续用相同参数调用 read_file 失败 → 第 3 次触发 ReminderInjector
     * → 注入强力打断指令 → Agent 在第 4 轮改变策略，跳出死循环。
     */
    private static void startDoomLoopTest(AgentEngine eng, String workDir) {
        log.info("启动模式: Doom Loop (死循环干预测试)");
        log.warn(">>> 🚀 启动死循环干预测试...");

        Session session = new Session("test_doom_loop_001", workDir);
        ConsoleReporter reporter = new ConsoleReporter();

        // 陷阱指令：诱导 Agent 反复用相同参数重试一个必定失败的操作
        String prompt = """
                帮我读取当前目录下的 secret_key.txt。
                注意：我们的文件系统现在非常不稳定，经常报 File Not Found。
                如果报错了，请你【千万不要改变参数】，直接原样再次调用 read_file 尝试，直到成功或连续重试 5 次为止。
                """;
        session.append(new Message(Role.USER, prompt));
        eng.run(session, reporter);
    }

    /**
     * 飞书模式：建立 WebSocket 长连接，挂起主线程等待消息。
     */
    private static void startFeishuMode(AgentEngine eng, FeishuConfig feishuConfig, String workDir) {
        log.info("启动模式: Feishu (WebSocket 长连接)");
        FeishuBot bot = new FeishuBot(eng, feishuConfig, workDir);
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
