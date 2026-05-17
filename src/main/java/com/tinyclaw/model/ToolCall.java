package com.tinyclaw.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 代表模型请求调用某个具体的工具。
 *
 * @param id        工具调用的唯一 ID
 * @param name      想要调用的工具名称（例如 "bash"）
 * @param arguments 存放 JSON 参数。使用 JsonNode 是为了延迟解析，
 *                  将解析责任交给具体的工具，实现 Main Loop 与工具参数的极致解耦。
 */
public record ToolCall(String id, String name, JsonNode arguments) {
    // JsonNode 就像一个万能容器，可以表示任何 JSON 数据（对象、数组、字符串、数字、布尔值、null）
}
