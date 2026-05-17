package com.tinyclaw.config;

/**
 * 引擎配置。
 *
 * @param enableThinking 是否开启慢思考模式
 * @param workDir        工作目录（Agent 的物理边界）
 */
public record EngineConfig(
        boolean enableThinking,
        String workDir) {
}
