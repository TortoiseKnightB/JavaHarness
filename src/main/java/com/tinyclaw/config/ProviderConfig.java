package com.tinyclaw.config;

/**
 * Provider 配置。
 *
 * @param type    Provider 类型：openai | claude | mock
 * @param model   模型名称
 * @param apiKey  API 密钥（支持 ${ENV_VAR:default} 占位符）
 * @param baseUrl API 基础地址
 */
public record ProviderConfig(
        String type,
        String model,
        String apiKey,
        String baseUrl) {
}
