package com.tinyclaw.feishu;

import com.lark.oapi.Client;
import com.lark.oapi.core.utils.Jsons;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.tinyclaw.config.FeishuConfig;
import com.tinyclaw.engine.AgentEngine;
import com.tinyclaw.engine.FeishuReporter;
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
     * 飞书 REST API 客户端（用于发送消息）
     */
    private final Client restClient;

    public FeishuBot(AgentEngine engine, FeishuConfig config) {
        this.engine = engine;
        this.config = config;
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
     * 为指定聊天窗口实例化 Reporter 并启动引擎。
     */
    private void handleAgentRun(String chatId, String prompt) {
        FeishuReporter reporter = new FeishuReporter(restClient, chatId);
        log.info("[Feishu] 启动 Agent 任务，chatId: {}, prompt: {}", chatId, prompt);
        try {
            engine.run(prompt, reporter);
        } catch (Exception e) {
            log.error("[Feishu] Agent 运行崩溃: {}", e.getMessage(), e);
            reporter.onMessage("❌ Agent 运行崩溃: " + e.getMessage());
        }
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
