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
        AgentEngine eng = new AgentEngine(p, r, engineConfig.enableThinking());

        // 5. 根据配置的模式启动
        String mode = serverConfig.mode();
        if ("feishu".equals(mode)) {
            startFeishuMode(eng, config.feishu(), workDir);
        } else if ("test".equals(mode)) {
            startTestMode(eng);
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
     * 飞书模式：建立 WebSocket 长连接，挂起主线程等待消息。
     */
    private static void startFeishuMode(AgentEngine eng, FeishuConfig feishuConfig, String workDir) {
        log.info("启动模式: Feishu (WebSocket 长连接)");
        FeishuBot bot = new FeishuBot(eng, feishuConfig, workDir);
        bot.start();  // 阻塞直到连接断开
    }

    /**
     * 测试模式：模拟并发场景，验证 Session 物理隔离与 Working Memory 截断。
     * <p>
     * 模拟两个飞书群同时请求同一个 AgentEngine：
     * Session A 读取 README.md 获取密钥 → 6 轮闲聊刷掉记忆 → 忘记密钥；
     * Session B 并发询问 → 看不到 Session A 的数据。
     */
    private static void startTestMode(AgentEngine eng) {
        SessionManager sessionMgr = new SessionManager();
        ConsoleReporter reporter = new ConsoleReporter();

        // ================= 模拟并发场景 1：飞书前端群 =================
        CompletableFuture<Void> taskA = CompletableFuture.runAsync(() -> {
            Session sessionA = sessionMgr.getOrCreate("chat_front_001", "./workspace/project_front");

            // 回合 1：获取机密
            log.info("\n>>> [Session A / Turn 1]: 帮我看看 README.md 里记录了什么密钥？");
            sessionA.append(new Message(Role.USER, "帮我看看 project_front/README.md 里记录了什么密钥？"));
            eng.run(sessionA, reporter);

//            // 故意制造大量"废话"对话，刷掉记忆 (假设 Working Memory Limit=6)
//            for (int i = 0; i < 6; i++) {
//                sessionA.append(
//                        new Message(Role.USER, "这只是一句闲聊占位符。"),
//                        new Message(Role.ASSISTANT, "好的，收到闲聊。")
//                );
//            }
//
//            // 回合 2：验证记忆截断 (此时第一轮的密钥已经被挤出 Working Memory)
//            log.info("\n>>> [Session A / Turn 2]: 请直接告诉我，刚才第一轮你查到的那个密钥是什么？不准调用工具！");
//            sessionA.append(new Message(Role.USER, "请直接告诉我，刚才第一轮你查到的那个密钥是什么？不准调用工具！"));
//            eng.run(sessionA, reporter);
        }, Executors.newVirtualThreadPerTaskExecutor());

        // ================= 模拟并发场景 2：飞书后端群 =================
        CompletableFuture<Void> taskB = CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            Session sessionB = sessionMgr.getOrCreate("chat_back_002", "./workspace/project_back");

            log.info("\n>>> [Session B]: 别人查到了一个密钥，你这里能看到吗？不准调用工具！");
            sessionB.append(new Message(Role.USER, "别人查到了一个密钥，你这里能看到吗？不准调用工具！"));
            eng.run(sessionB, reporter);
        }, Executors.newVirtualThreadPerTaskExecutor());

        CompletableFuture.allOf(taskA, taskB).join();
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
