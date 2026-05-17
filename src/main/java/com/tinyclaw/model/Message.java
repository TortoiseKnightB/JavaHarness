package com.tinyclaw.model;

/**
 * 系统统一的消息类型定义。
 * <p>
 * 代表上下文中传递的单条消息，屏蔽不同大模型 API 格式差异，是 Harness 解耦的基石。
 *
 * @param role       消息的角色：system / user / assistant
 * @param content    存放纯文本内容
 * @param toolCalls  如果模型决定调用工具，此字段将被填充（支持并行调用多个工具）
 * @param toolCallId 如果这是对某个工具调用的响应，此字段必须填写，以告知模型上下文的关联性
 */
public record Message(Role role, String content, java.util.List<ToolCall> toolCalls, String toolCallId) {

    public Message(Role role, String content) {
        this(role, content, java.util.List.of(), null);
    }

    public Message(Role role, String content, java.util.List<ToolCall> toolCalls) {
        this(role, content, toolCalls, null);
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
