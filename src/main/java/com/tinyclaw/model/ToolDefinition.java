package com.tinyclaw.model;

import java.util.Map;

/**
 * 描述一个可供大模型调用的工具元信息。
 * <p>
 * 供模型理解工具有什么用，inputSchema 遵循 JSON Schema 规范。

 * @param name        工具的唯一名称
 * @param description 工具的功能描述
 * @param inputSchema JSON Schema 定义的工具输入参数规范
 */
public record ToolDefinition(String name, String description, Map<String, Object> inputSchema) {
}
