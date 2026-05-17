package com.tinyclaw.model;

/**
 * 定义消息的角色，这是与大模型沟通的基石。
 */
public enum Role {

    /** 系统提示词：确立 Agent 的性格与红线 */
    SYSTEM("system"),
    /** 用户输入 / 工具执行的返回结果 (Observation) */
    USER("user"),
    /** 模型的输出：包含推理(Reasoning)或工具调用(ToolCall) */
    ASSISTANT("assistant");

    /** 角色对应的字符串值，用于序列化为 JSON */
    private final String value;

    Role(String value) {
        this.value = value;
    }

    /** 返回角色的 JSON 序列化值 */
    public String value() {
        return value;
    }
}
