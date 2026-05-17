package com.tinyclaw.model;

/**
 * 代表工具在本地执行完毕后返回的物理结果（Observation）。
 * <p>
 * isError 标记失败状态，供后续的驾驭工程进行错误自愈。
 *
 * @param toolCallId 关联的工具调用 ID
 * @param output     工具执行的控制台输出或报错堆栈
 * @param isError    标记是否失败
 */
public record ToolResult(String toolCallId, String output, boolean isError) {

    public static ToolResult success(String toolCallId, String output) {
        return new ToolResult(toolCallId, output, false);
    }

    public static ToolResult error(String toolCallId, String output) {
        return new ToolResult(toolCallId, output, true);
    }
}
