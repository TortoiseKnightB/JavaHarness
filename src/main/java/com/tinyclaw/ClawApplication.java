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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

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
        } else if ("recovery".equals(mode)) {
            startRecoveryTest(eng, workDir);
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
     * Recovery 测试：创建 auth.go 靶机文件，用包含错误 old_text 的指令诱导 Agent 失败，
     * 验证 RecoveryManager 注入锦囊后 Agent 自愈闭环。
     * <p>
     * Turn 1：Agent 直接用错误的 old_text 调用 edit_file → 失败 → 注入锦囊
     * Turn 2：Agent 按锦囊指示先 read_file → 获取准确内容 → edit_file 成功。
     */
    private static void startRecoveryTest(AgentEngine eng, String workDir) {
        log.info("启动模式: Recovery (错误自愈测试)");

        // 准备"靶机"代码：auth.go
        String authCode = """
                package main

                func login(user string) bool {
                    // 检查用户名
                    if user == "admin" {
                        return true
                    }
                    return false
                }
                """;
        try {
            Files.writeString(Path.of(workDir, "auth.go"), authCode);
            log.info("[Recovery] 已创建 auth.go");
        } catch (IOException e) {
            log.error("[Recovery] auth.go 创建失败: {}", e.getMessage());
            return;
        }

        Session session = new Session("test_recovery_001", workDir);
        ConsoleReporter reporter = new ConsoleReporter();

        // 陷阱指令：故意在 old_text 中加了一句不存在的注释 "// 鉴权入口函数"，
        // 诱导 edit_file 因 old_text 不匹配而失败
        String prompt = """
                我当前目录下有一个 auth.go 文件。
                请修改 auth.go 中的 login 函数。
                你可以不用读取源文件，请直接使用 edit_file 工具替换下面的代码块，将判断条件改为同时允许"admin"、"root"和"guest"三种用户登录：

                // 鉴权入口函数
                func login(user string) bool {
                    // 检查用户名
                    if user == "admin" {
                        return true
                    }
                    return false
                }
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
