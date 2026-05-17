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
 * 基于 Anthropic（Claude）协议的 Provider。
 * <p>
 * 支持所有 Anthropic-compatible API（智谱 Claude 协议 / 原生 Anthropic），只需替换 baseUrl。
 */
public class ClaudeProvider extends AbstractHttpProvider {

    /**
     * @param model   模型名称（如 "glm-4.5-air"）
     * @param apiKey  API 密钥
     * @param baseUrl API 基础地址
     */
    public ClaudeProvider(String model, String apiKey, String baseUrl) {
        super(model, apiKey, baseUrl, "messages");
    }

    /**
     * {@inheritDoc}
     * <p>
     * Anthropic 协议使用 x-api-key 认证头和 anthropic-version 版本声明。
     *
     * @return 包含 x-api-key 和 anthropic-version 的请求头映射
     */
    @Override
    protected Map<String, String> authHeaders() {
        return Map.of(
                "x-api-key", apiKey,
                "anthropic-version", "2023-06-01"
        );
    }

    /**
     * {@inheritDoc}
     * <p>
     * 将内部 Message 序列翻译为 Anthropic 兼容的 /messages 请求体。
     * system 提示词提升到顶层字段，消息转换为 content blocks 数组，工具参数转 input_schema 格式。
     *
     * @param messages       当前上下文历史
     * @param availableTools 当前挂载的工具定义（空列表时不包含 tools 字段，实现两阶段隔离）
     * @return Anthropic 格式的 JSON 请求体
     */
    @Override
    protected ObjectNode buildRequestBody(List<Message> messages, List<ToolDefinition> availableTools) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", 4096);

        // 1. 翻译消息：system 提升到顶层，其余转为 content blocks
        StringBuilder systemPrompt = new StringBuilder();
        ArrayNode msgsArray = body.putArray("messages");

        for (Message msg : messages) {
            if (msg.role() == Role.SYSTEM) {
                systemPrompt.append(msg.content());
                continue;
            }

            ObjectNode msgNode = mapper.createObjectNode();
            switch (msg.role()) {
                case USER:
                    msgNode.put("role", "user");
                    ArrayNode userContent = msgNode.putArray("content");
                    if (msg.toolCallId() != null && !msg.toolCallId().isEmpty()) {
                        // 工具执行反馈 → tool_result block
                        ObjectNode trBlock = userContent.addObject();
                        trBlock.put("type", "tool_result");
                        trBlock.put("tool_use_id", msg.toolCallId());
                        trBlock.put("content", msg.content());
                    } else {
                        ObjectNode textBlock = userContent.addObject();
                        textBlock.put("type", "text");
                        textBlock.put("text", msg.content());
                    }
                    break;

                case ASSISTANT:
                    msgNode.put("role", "assistant");
                    ArrayNode asstContent = msgNode.putArray("content");
                    // 文本部分
                    if (msg.content() != null && !msg.content().isEmpty()) {
                        ObjectNode textBlock = asstContent.addObject();
                        textBlock.put("type", "text");
                        textBlock.put("text", msg.content());
                    }
                    // 历史 ToolCall → tool_use block
                    if (msg.hasToolCalls()) {
                        for (ToolCall tc : msg.toolCalls()) {
                            ObjectNode tuBlock = asstContent.addObject();
                            tuBlock.put("type", "tool_use");
                            tuBlock.put("id", tc.id());
                            tuBlock.put("name", tc.name());
                            tuBlock.putPOJO("input", argsToMap(tc.arguments()));
                        }
                    }
                    break;
            }
            msgsArray.add(msgNode);
        }

        if (!systemPrompt.isEmpty()) {
            body.put("system", systemPrompt.toString());
        }

        // 2. 翻译工具定义（仅当非空时挂载——支撑两阶段架构）
        if (!availableTools.isEmpty()) {
            ArrayNode toolsArray = body.putArray("tools");
            for (ToolDefinition toolDef : availableTools) {
                ObjectNode toolNode = toolsArray.addObject();
                toolNode.put("name", toolDef.name());
                toolNode.put("description", toolDef.description());
                toolNode.putPOJO("input_schema", toolDef.inputSchema());
            }
        }

        return body;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 解析 Anthropic 响应体，遍历 content 数组提取 text 和 tool_use 块。
     *
     * @param responseBody API 返回的 JSON 响应体
     * @return 内部 Message（含纯文本或工具调用）
     */
    @Override
    protected Message parseResponse(ObjectNode responseBody) {
        StringBuilder contentBuilder = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();

        for (JsonNode block : responseBody.path("content")) {
            String type = block.path("type").asText();
            switch (type) {
                case "text":
                    contentBuilder.append(block.path("text").asText());
                    break;
                case "tool_use":
                    JsonNode inputNode = block.path("input");
                    String argsStr;
                    try {
                        argsStr = mapper.writeValueAsString(inputNode);
                    } catch (JsonProcessingException e) {
                        argsStr = "{}";
                    }
                    JsonNode argsNode;
                    try {
                        argsNode = mapper.readTree(argsStr);
                    } catch (JsonProcessingException e) {
                        argsNode = mapper.createObjectNode();
                    }
                    toolCalls.add(new ToolCall(
                            block.get("id").asText(),
                            block.get("name").asText(),
                            argsNode
                    ));
                    break;
            }
        }

        return new Message(Role.ASSISTANT, contentBuilder.toString(), toolCalls);
    }
}
