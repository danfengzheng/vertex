package com.vertex.framework.socket.reconnect;

import lombok.extern.slf4j.Slf4j;

/**
 * 指数退避重连策略
 * <p>
 * 延迟公式：min(initialDelay * multiplier^(attempt-1), maxDelay)
 */
@Slf4j
public class ExponentialBackoffPolicy implements ReconnectPolicy {

    private final long initialDelay;
    private final long maxDelay;
    private final double multiplier;
    private final int maxAttempts;

    /**
     * @param initialDelay 初始延迟（毫秒）
     * @param maxDelay     最大延迟（毫秒）
     * @param multiplier   退避倍数
     * @param maxAttempts  最大重试次数，-1 表示无限重试
     */
    public ExponentialBackoffPolicy(long initialDelay, long maxDelay, double multiplier, int maxAttempts) {
        this.initialDelay = initialDelay;
        this.maxDelay = maxDelay;
        this.multiplier = multiplier;
        this.maxAttempts = maxAttempts;
    }

    public ExponentialBackoffPolicy() {
        this(1000L, 60000L, 2.0, -1);
    }

    @Override
    public long nextDelay(int attempt) {
        if (!shouldRetry(attempt)) {
            return -1;
        }
        long delay = (long) (initialDelay * Math.pow(multiplier, attempt - 1));
        return Math.min(delay, maxDelay);
    }

    @Override
    public boolean shouldRetry(int attempt) {
        return maxAttempts < 0 || attempt <= maxAttempts;
    }

    @Override
    public void reset() {
        // 无状态，无需重置
    }
}
