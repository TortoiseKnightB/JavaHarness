package com.tinyclaw.feishu;

import com.lark.oapi.Client;
import com.lark.oapi.core.utils.Jsons;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.tinyclaw.config.FeishuConfig;
import com.tinyclaw.engine.AgentEngine;
import com.tinyclaw.engine.FeishuReporter;
import com.tinyclaw.engine.Session;
import com.tinyclaw.engine.Session.SessionManager;
import com.tinyclaw.feishu.ApprovalManager;
import com.tinyclaw.model.Message;
import com.tinyclaw.model.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 飞书机器人：基于 WebSocket 长连接接收事件，驱动 AgentEngine 执行任务。
 * <p>
 * 使用飞书官方 SDK 的 ws.Client 建立长连接，无需公网 IP。
 */
public class FeishuBot {

    private static final Logger log = LoggerFactory.getLogger(FeishuBot.class);

    /**
     * JSON 解析器，用于解析飞书事件数据
     */
    private static final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * 虚拟线程执行器，每个飞书消息启动独立线程处理
     */
    private static final Executor VIRTUAL_THREADS = Executors.newVirtualThreadPerTaskExecutor();

    private final AgentEngine engine;
    private final FeishuConfig config;

    /**
     * 工作区物理边界目录（所有飞书会话共享同一工作区）
     */
    private final String workDir;

    /**
     * 全局会话管理器，每个飞书 chatId 对应一个独立 Session
     */
    private final SessionManager sessionMgr;

    /**
     * 飞书 REST API 客户端（用于发送消息）
     */
    private final Client restClient;

    /**
     * 当前正在执行的 Reporter（供 Middleware 获取飞书消息发送能力）
     */
    private volatile FeishuReporter reporter;

    /**
     * @param engine  Agent 核心引擎
     * @param config  飞书 SDK 配置
     * @param workDir 工作区根目录
     */
    public FeishuBot(AgentEngine engine, FeishuConfig config, String workDir) {
        this.engine = engine;
        this.config = config;
        this.workDir = workDir;
        this.sessionMgr = new SessionManager();
        this.restClient = Client.newBuilder(config.appId(), config.appSecret()).build();
    }

    /**
     * 启动飞书机器人：建立 WebSocket 长连接，挂起主线程。
     */
    public void start() {
        EventDispatcher eventHandler = EventDispatcher.newBuilder("", "")
                .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                    @Override
                    public void handle(P2MessageReceiveV1 event) throws Exception {
                        // 将事件数据转为 JSON 后用 Jackson 解析提取 chatId 和文本内容
                        String eventJson = Jsons.DEFAULT.toJson(event.getEvent());
                        com.fasterxml.jackson.databind.JsonNode eventNode = mapper.readTree(eventJson);

                        String chatId = eventNode.path("message").path("chat_id").asText();
                        String content = eventNode.path("message").path("content").asText();

                        log.info("[Feishu] 收到会话 {} 的消息: {}", chatId, content);

                        // 提取文本内容
                        String userText = extractText(content);

                        // 【新增】拦截人工审批的特殊口令
                        if (userText.startsWith("approve ")) {
                            String taskId = userText.substring("approve ".length()).trim();
                            ApprovalManager.INSTANCE.resolveApproval(taskId, true, "人类管理员已批准操作");
                            log.info("[Feishu] 会话 {}: ✅ 已批准任务 {}", chatId, taskId);
                            return;
                        }
                        if (userText.startsWith("reject ")) {
                            String taskId = userText.substring("reject ".length()).trim();
                            ApprovalManager.INSTANCE.resolveApproval(taskId, false, "人类管理员认为该操作存在极高风险，已无情拒绝");
                            log.info("[Feishu] 会话 {}: 🚫 已拒绝任务 {}", chatId, taskId);
                            return;
                        }

                        // 异步启动 Agent 任务，不阻塞 WebSocket 回调（3 秒内必须返回）
                        VIRTUAL_THREADS.execute(() -> {
                            handleAgentRun(chatId, userText);
                        });
                    }
                })
                .build();

        com.lark.oapi.ws.Client wsClient = new com.lark.oapi.ws.Client.Builder(
                config.appId(), config.appSecret())
                .eventHandler(eventHandler)
                .build();

        log.info("[Feishu] 正在建立 WebSocket 长连接到飞书服务器...");
        wsClient.start();
    }

    /**
     * 为指定聊天窗口获取或创建 Session，追加用户消息后启动引擎。
     */
    private void handleAgentRun(String chatId, String prompt) {
        Session session = sessionMgr.getOrCreate(chatId, workDir);
        session.append(new Message(Role.USER, prompt));
        FeishuReporter r = new FeishuReporter(restClient, chatId);
        this.reporter = r;
        log.info("[Feishu] 启动 Agent 任务，chatId: {}, prompt: {}", chatId, prompt);
        try {
            engine.run(session, r);
        } catch (Exception e) {
            log.error("[Feishu] Agent 运行崩溃: {}", e.getMessage(), e);
            r.onMessage("❌ Agent 运行崩溃: " + e.getMessage());
        } finally {
            this.reporter = null;
        }
    }

    /**
     * 返回当前正在执行的 Reporter，供 Middleware 发送审批消息。
     *
     * @return 当前 Reporter，可能为 null
     */
    public FeishuReporter reporter() {
        return reporter;
    }

    /**
     * 从飞书消息 JSON 中提取用户发送的纯文本。
     * 飞书消息 content 格式：{"text":"用户文字"}
     */
    private String extractText(String content) {
        try {
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(content);
            if (node.has("text")) {
                return node.get("text").asText();
            }
        } catch (Exception ignored) {
        }
        // 降级处理：去掉可能的 JSON 包裹字符
        return content.replaceAll("^\\{\"text\":\"", "").replaceAll("\"}$", "");
    }
}
