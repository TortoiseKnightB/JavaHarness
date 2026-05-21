package com.tinyclaw.config;

/**
 * 服务器运行模式配置。
 *
 * @param mode 启动模式：cli | feishu
 */
public record ServerConfig(String mode) {
}
