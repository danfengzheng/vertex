package com.vertex.framework.socket.reconnect;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 自动重连 Handler
 * <p>
 * 监听 channelInactive 事件，按照 ReconnectPolicy 策略进行重连。
 * </p>
 * <p>
 * <b>Bug-fix（线上事故）</b>：原实现使用 {@code ctx.channel().eventLoop().schedule(...)}
 * 调度重连任务。但 {@code reconnect()} 内部 {@code disconnect()} 会 shutdown 整个
 * NioEventLoopGroup，包含 {@code ctx.channel()} 所在的 eventLoop。后续重试 schedule
 * 会因 eventLoop 已 terminated 而抛 RejectedExecutionException，导致重连机制失效。
 * 修复：改用进程级独立 daemon ScheduledExecutorService，生命周期不受 channel 影响。
 * </p>
 */
@Slf4j
public class ReconnectHandler extends ChannelInboundHandlerAdapter {

    /**
     * 全局共享的重连调度器（daemon 线程）。
     * <p>
     * 独立于任何 channel 的 eventLoop，确保 group.shutdownGracefully() 之后
     * 仍能继续调度下一次重连任务。
     * </p>
     */
    private static final ScheduledExecutorService RECONNECT_SCHEDULER =
            Executors.newScheduledThreadPool(1, r -> {
                Thread t = new Thread(r, "ws-reconnect-scheduler");
                t.setDaemon(true);
                return t;
            });

    private final ReconnectPolicy policy;
    private final Runnable reconnectTask;
    private final AtomicInteger attempts = new AtomicInteger(0);
    private volatile boolean reconnecting = false;
    /** 用户主动停止标志：disconnect() 显式调用 stopReconnect() 时置 true，阻止后续重连 */
    private volatile boolean stopped = false;

    /**
     * @param policy        重连策略
     * @param reconnectTask 重连动作（由外部提供，通常是 WebSocketClient::reconnect）
     */
    public ReconnectHandler(ReconnectPolicy policy, Runnable reconnectTask) {
        this.policy = policy;
        this.reconnectTask = reconnectTask;
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (!reconnecting && !stopped) {
            scheduleReconnect();
        }
        super.channelInactive(ctx);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        // 连接成功，重置重试计数与状态标志
        attempts.set(0);
        reconnecting = false;
        stopped = false;
        policy.reset();
        log.info("Connection established: {}", ctx.channel().remoteAddress());
        super.channelActive(ctx);
    }

    /**
     * 调度下一次重连（用全局独立调度器，不依赖任何 channel 的 eventLoop）。
     */
    private void scheduleReconnect() {
        if (stopped) {
            log.info("Reconnect stopped by user, not scheduling further attempts");
            return;
        }
        int attempt = attempts.incrementAndGet();
        if (!policy.shouldRetry(attempt)) {
            log.warn("Max reconnect attempts reached ({}), giving up", attempt - 1);
            reconnecting = false;
            return;
        }

        long delay = policy.nextDelay(attempt);
        if (delay < 0) {
            reconnecting = false;
            return;
        }

        reconnecting = true;
        log.info("Scheduling reconnect attempt {} in {}ms", attempt, delay);

        try {
            RECONNECT_SCHEDULER.schedule(() -> {
                if (stopped) {
                    log.info("Reconnect stopped before attempt {} could execute", attempt);
                    reconnecting = false;
                    return;
                }
                log.info("Executing reconnect attempt {}", attempt);
                try {
                    reconnectTask.run();
                    // reconnectTask 内部成功并触发 channelActive → 会重置 attempts/reconnecting
                    // 这里不做处理；若 task 抛异常说明失败，走 catch 调度下一次重连
                } catch (Throwable t) {
                    log.error("Reconnect attempt {} failed", attempt, t);
                    // 失败后继续调度下一次重连（用同一调度器，不再依赖 channel 的 eventLoop）
                    scheduleReconnect();
                }
            }, delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // 全局调度器一般不会被关闭（JVM 生命周期），除非显式 shutdown
            log.error("Failed to schedule reconnect attempt {}: scheduler rejected", attempt, e);
            reconnecting = false;
        }
    }

    /**
     * 手动停止重连（disconnect() 主动关闭时调用，区别于"网络断开"）。
     */
    public void stopReconnect() {
        stopped = true;
        reconnecting = false;
    }

    /**
     * 重置重连状态
     */
    public void resetReconnect() {
        reconnecting = false;
        stopped = false;
        attempts.set(0);
        policy.reset();
    }
}
