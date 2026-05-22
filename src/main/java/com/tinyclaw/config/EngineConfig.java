package com.tinyclaw.config;

/**
 * 引擎配置。
 *
 * @param enableThinking 是否开启慢思考模式
 * @param planMode       是否开启计划模式（注入文件系统状态外部化规范）
 * @param workDir        工作目录（Agent 的物理边界）
 */
public record EngineConfig(
        boolean enableThinking,
        boolean planMode,
        String workDir) {
}
