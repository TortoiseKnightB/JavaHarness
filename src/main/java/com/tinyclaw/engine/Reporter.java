package com.tinyclaw.engine;

/**
 * 定义了 Agent 引擎向外界输出信息的规范。
 * <p>
 * 使引擎可以无缝切换终端 (CLI)、飞书、钉钉甚至 WebUI 等不同的展现层。
 */
public interface Reporter {

    /**
     * 当模型开始进行慢思考 (Phase 1 Thinking) 时调用。
     */
    void onThinking();

    /**
     * 当模型决定并发调用工具时调用。
     *
     * @param toolName 工具名称
     * @param args     工具参数 JSON
     */
    void onToolCall(String toolName, String args);

    /**
     * 当工具在底层执行完毕并返回结果时调用。
     *
     * @param toolName 工具名称
     * @param result   执行结果字符串
     * @param isError  是否执行失败
     */
    void onToolResult(String toolName, String result, boolean isError);

    /**
     * 当模型宣告任务完成，向用户输出最终纯文本回答时调用。
     *
     * @param content 模型输出的最终内容
     */
    void onMessage(String content);
}
