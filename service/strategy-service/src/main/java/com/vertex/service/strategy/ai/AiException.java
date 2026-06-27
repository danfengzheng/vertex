package com.vertex.service.strategy.ai;

/**
 * AI 调用异常（与具体 provider 无关）。
 * <p>
 * 携带 {@code retryable} 标记，让 client 内部退避重试时区分网络/5xx（可重试）
 * 与 4xx/解析错误（不可重试）。
 * </p>
 */
public class AiException extends Exception {
    private final boolean retryable;

    public AiException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
