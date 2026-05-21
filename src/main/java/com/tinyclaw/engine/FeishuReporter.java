package com.tinyclaw.engine;

import com.lark.oapi.Client;
import com.lark.oapi.core.utils.Jsons;
import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody;
import com.lark.oapi.service.im.v1.model.CreateMessageResp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 飞书 Reporter：将引擎的输出通过飞书 REST API 发送给对应聊天窗口。
 */
public class FeishuReporter implements Reporter {

    private static final Logger log = LoggerFactory.getLogger(FeishuReporter.class);

    /**
     * 飞书 REST API 客户端（用于发送消息，不是 WebSocket 客户端）
     */
    private final Client restClient;

    /**
     * 飞书会话 ID
     */
    private final String chatId;

    /**
     * @param restClient 飞书 REST API 客户端
     * @param chatId     接收消息的飞书会话 ID
     */
    public FeishuReporter(Client restClient, String chatId) {
        this.restClient = restClient;
        this.chatId = chatId;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onThinking() {
        sendMsg("🧠 正在深度思考与规划...");
    }

    /**
     * {@inheritDoc}
     *
     * @param toolName 工具名称
     * @param args     工具参数 JSON 字符串
     */
    @Override
    public void onToolCall(String toolName, String args) {
        sendMsg("🛠️ 正在调用工具: `" + toolName + "`\n参数: " + truncate(args, 200));
    }

    /**
     * {@inheritDoc}
     *
     * @param toolName 工具名称
     * @param result   执行结果字符串
     * @param isError  是否执行失败
     */
    @Override
    public void onToolResult(String toolName, String result, boolean isError) {
        String emoji = isError ? "❌" : "✅";
        String label = isError ? "执行报错" : "执行成功";
        sendMsg(emoji + " 工具 `" + toolName + "` " + label + "\n" + truncate(result, 500));
    }

    /**
     * {@inheritDoc}
     *
     * @param content 模型输出的最终内容
     */
    @Override
    public void onMessage(String content) {
        sendMsg(content);
    }

    /**
     * 调用飞书 REST API 发送文本消息到指定会话。
     *
     * @param text 要发送的消息文本
     */
    private void sendMsg(String text) {
        try {
            String msgContent = Jsons.DEFAULT.toJson(Map.of("text", text));
            CreateMessageReq req = CreateMessageReq.newBuilder()
                    .receiveIdType("chat_id")
                    .createMessageReqBody(CreateMessageReqBody.newBuilder()
                            .receiveId(chatId)
                            .msgType("text")
                            .content(msgContent)
                            .build())
                    .build();

            CreateMessageResp resp = restClient.im().message().create(req);
            if (!resp.success()) {
                log.warn("[Feishu] 发送消息失败: code={} msg={}", resp.getCode(), resp.getMsg());
            }
        } catch (Exception e) {
            log.warn("[Feishu] 发送消息异常: {}", e.getMessage());
        }
    }

    /**
     * 截断过长字符串。
     *
     * @param s      原始字符串
     * @param maxLen 最大长度
     * @return 截断后的字符串，null 返回空串
     */
    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
