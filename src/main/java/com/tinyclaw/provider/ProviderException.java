package com.tinyclaw.provider;

/**
 * Provider 层异常，包装 API 错误和网络故障。
 */
public class ProviderException extends RuntimeException {

    /**
     * @param message 错误描述（通常是 API 返回的错误信息）
     */
    public ProviderException(String message) {
        super(message);
    }

    /**
     * @param message 错误描述
     * @param cause   底层原因（如 IOException）
     */
    public ProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
