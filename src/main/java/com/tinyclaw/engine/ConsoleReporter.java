package com.tinyclaw.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CLI 模式下的终端输出 Reporter。
 */
public class ConsoleReporter implements Reporter {

    private static final Logger log = LoggerFactory.getLogger(ConsoleReporter.class);

    @Override
    public void onThinking() {
        log.info("[Engine] 正在思考 (Reasoning)...");
    }

    @Override
    public void onToolCall(String toolName, String args) {
        log.info(" -> 🛠️ 执行工具: {}, 参数: {}", toolName, args);
    }

    @Override
    public void onToolResult(String toolName, String result, boolean isError) {
        if (isError) {
            log.info(" -> ❌ 工具执行报错: {}", result);
        } else {
            log.info(" -> ✅ 工具执行成功 (返回 {} 字节)", result.length());
        }
    }

    @Override
    public void onMessage(String content) {
        System.out.println("🤖 [对外回复]: " + content);
    }
}
