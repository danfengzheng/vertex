package com.vertex.framework.socket.reconnect;

/**
 * 重连策略接口
 */
public interface ReconnectPolicy {

    /**
     * 计算下次重连延迟（毫秒）
     *
     * @param attempt 当前第几次重试（从1开始）
     * @return 延迟毫秒数，返回 -1 表示停止重试
     */
    long nextDelay(int attempt);

    /**
     * 是否继续重试
     */
    boolean shouldRetry(int attempt);

    /**
     * 重置策略状态
     */
    void reset();
}
