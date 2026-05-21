package com.tinyclaw.config;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 飞书应用配置。
 *
 * @param appId         App ID（开发者后台获取）
 * @param appSecret     App Secret
 */
public record FeishuConfig(
        @JsonProperty("app-id") String appId,
        @JsonProperty("app-secret") String appSecret) {
}
