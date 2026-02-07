package com.vertex.framework.socket.reconnect;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 自动重连 Handler
 * <p>
 * 监听 channelInactive 事件，按照 ReconnectPolicy 策略进行重连
 */
@Slf4j
public class ReconnectHandler extends ChannelInboundHandlerAdapter {

    private final ReconnectPolicy policy;
    private final Runnable reconnectTask;
    private final AtomicInteger attempts = new AtomicInteger(0);
    private volatile boolean reconnecting = false;

    /**
     * @param policy        重连策略
     * @param reconnectTask 重连动作（由外部提供，通常是 WebSocketClient::connect）
     */
    public ReconnectHandler(ReconnectPolicy policy, Runnable reconnectTask) {
        this.policy = policy;
        this.reconnectTask = reconnectTask;
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (!reconnecting) {
            scheduleReconnect(ctx);
        }
        super.channelInactive(ctx);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        // 连接成功，重置重试计数
        attempts.set(0);
        reconnecting = false;
        policy.reset();
        log.info("Connection established: {}", ctx.channel().remoteAddress());
        super.channelActive(ctx);
    }

    private void scheduleReconnect(ChannelHandlerContext ctx) {
        int attempt = attempts.incrementAndGet();
        if (!policy.shouldRetry(attempt)) {
            log.warn("Max reconnect attempts reached ({}), giving up", attempt - 1);
            return;
        }

        long delay = policy.nextDelay(attempt);
        if (delay < 0) {
            return;
        }

        reconnecting = true;
        log.info("Scheduling reconnect attempt {} in {}ms", attempt, delay);

        ctx.channel().eventLoop().schedule(() -> {
            log.info("Executing reconnect attempt {}", attempt);
            try {
                reconnectTask.run();
            } catch (Exception e) {
                log.error("Reconnect attempt {} failed", attempt, e);
                // 失败后继续调度下一次重连
                scheduleReconnect(ctx);
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    /**
     * 手动停止重连
     */
    public void stopReconnect() {
        reconnecting = false;
        attempts.set(Integer.MAX_VALUE);
    }

    /**
     * 重置重连状态
     */
    public void resetReconnect() {
        reconnecting = false;
        attempts.set(0);
        policy.reset();
    }
}
