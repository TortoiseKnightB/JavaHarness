package com.tinyclaw.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tinyclaw.model.Message;
import com.tinyclaw.model.Role;
import com.tinyclaw.model.ToolCall;
import com.tinyclaw.model.ToolDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 基于 OpenAI 兼容协议的 Provider。
 * <p>
 * 支持所有 OpenAI-compatible API（智谱 / DeepSeek / 通义千问 等），只需替换 baseUrl 和 model。
 */
public class OpenAICompatProvider extends AbstractHttpProvider {

    /**
     * @param model   模型名称（如 "glm-4.5-air"）
     * @param apiKey  API 密钥
     * @param baseUrl API 基础地址
     */
    public OpenAICompatProvider(String model, String apiKey, String baseUrl) {
        super(model, apiKey, baseUrl, "chat/completions");
    }

    /**
     * {@inheritDoc}
     * <p>
     * 将内部 Message 序列翻译为 OpenAI 兼容的 chat/completions 请求体。
     *
     * @param messages       当前上下文历史
     * @param availableTools 当前挂载的工具定义（空列表时包含 tools 字段为"不挂载"，实现两阶段隔离）
     * @return OpenAI 格式的 JSON 请求体
     */
    @Override
    protected ObjectNode buildRequestBody(List<Message> messages, List<ToolDefinition> availableTools) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);

        // 1. 翻译上下文消息
        ArrayNode msgsArray = body.putArray("messages");
        for (Message msg : messages) {
            ObjectNode msgNode = mapper.createObjectNode();
            switch (msg.role()) {
                case SYSTEM:
                    msgNode.put("role", "system");
                    msgNode.put("content", msg.content());
                    break;

                case USER:
                    if (msg.toolCallId() != null && !msg.toolCallId().isEmpty()) {
                        // 工具执行观察结果 → role: tool
                        msgNode.put("role", "tool");
                        msgNode.put("tool_call_id", msg.toolCallId());
                        msgNode.put("content", msg.content());
                    } else {
                        msgNode.put("role", "user");
                        msgNode.put("content", msg.content());
                    }
                    break;

                case ASSISTANT:
                    msgNode.put("role", "assistant");
                    if (msg.content() != null && !msg.content().isEmpty()) {
                        msgNode.put("content", msg.content());
                    }
                    // 保持历史 ToolCall（维系大模型逻辑链的关键）
                    if (msg.hasToolCalls()) {
                        ArrayNode tcArray = msgNode.putArray("tool_calls");
                        for (ToolCall tc : msg.toolCalls()) {
                            ObjectNode tcNode = tcArray.addObject();
                            tcNode.put("id", tc.id());
                            tcNode.put("type", "function");
                            ObjectNode funcNode = tcNode.putObject("function");
                            funcNode.put("name", tc.name());
                            funcNode.put("arguments", tc.arguments().toString());
                        }
                    }
                    break;
            }
            msgsArray.add(msgNode);
        }

        // 2. 翻译工具定义（仅当 availableTools 非空时挂载——支撑两阶段架构）
        if (!availableTools.isEmpty()) {
            ArrayNode toolsArray = body.putArray("tools");
            for (ToolDefinition toolDef : availableTools) {
                ObjectNode toolNode = toolsArray.addObject();
                toolNode.put("type", "function");
                ObjectNode funcNode = toolNode.putObject("function");
                funcNode.put("name", toolDef.name());
                funcNode.put("description", toolDef.description());
                funcNode.putPOJO("parameters", toolDef.inputSchema());
            }
        }

        return body;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 解析 OpenAI 兼容响应体，提取 choices[0].message 中的文本和 tool_calls。
     *
     * @param responseBody API 返回的 JSON 响应体
     * @return 内部 Message（含纯文本或工具调用）
     */
    @Override
    protected Message parseResponse(ObjectNode responseBody) {
        JsonNode choice = responseBody.path("choices").get(0);
        JsonNode msg = choice.path("message");

        String content = msg.has("content") && !msg.get("content").isNull()
                ? msg.get("content").asText()
                : "";

        List<ToolCall> toolCalls = new ArrayList<>();
        if (msg.has("tool_calls")) {
            for (JsonNode tc : msg.get("tool_calls")) {
                JsonNode func = tc.path("function");
                String arguments = func.path("arguments").asText();
                JsonNode argsNode;
                try {
                    argsNode = mapper.readTree(arguments);
                } catch (JsonProcessingException e) {
                    argsNode = mapper.createObjectNode();
                }
                toolCalls.add(new ToolCall(tc.get("id").asText(), func.get("name").asText(), argsNode));
            }
        }

        return new Message(Role.ASSISTANT, content, toolCalls);
    }
}
