package com.tinyclaw.config;

/**
 * 应用全局配置。
 */
public record AppConfig(
        ProviderConfig provider,
        EngineConfig engine) {
}
