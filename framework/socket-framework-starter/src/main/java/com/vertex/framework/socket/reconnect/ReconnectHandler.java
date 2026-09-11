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
     * <b>Bug-fix（线上事故 2026-09-03）</b>：单线程池若某次 reconnect 任务 hang 住
     * （比如 handshake 无超时阻塞），后续所有重连任务都会排队死锁。
     * 改为 2 线程池：一个跑当前任务，另一个即使前者 hang 住也能继续调度新任务。
     * </p>
     */
    private static final ScheduledExecutorService RECONNECT_SCHEDULER =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "ws-reconnect-scheduler");
                t.setDaemon(true);
                return t;
            });

    /**
     * <b>Bug-fix（线上事故 2026-09-03）</b>：reconnecting=true 若因未知原因永远没被 reset
     * （例：调度线程 hang 在 handshake sync），后续 channelInactive 都会被
     * {@code if (!reconnecting)} 挡住 → 系统永久停机。
     * 加时间戳保护：距离 reconnectingSince > 该阈值时强制 reset。
     */
    private static final long RECONNECTING_STUCK_THRESHOLD_MS = 5 * 60_000L;

    private final ReconnectPolicy policy;
    private final Runnable reconnectTask;
    private final AtomicInteger attempts = new AtomicInteger(0);
    private volatile boolean reconnecting = false;
    private volatile long reconnectingSinceMs = 0;
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
        // Bug-fix：reconnecting 卡死保护 —— 如果距离 reconnectingSince 已超阈值仍未成功也未复位，
        // 强制 reset 并触发新一轮 scheduleReconnect（避免"永久停机"）。
        if (reconnecting && reconnectingSinceMs > 0
                && System.currentTimeMillis() - reconnectingSinceMs > RECONNECTING_STUCK_THRESHOLD_MS) {
            log.warn("[ReconnectHandler] reconnecting flag stuck for {}ms, force reset",
                    System.currentTimeMillis() - reconnectingSinceMs);
            reconnecting = false;
            reconnectingSinceMs = 0;
        }

        if (!reconnecting && !stopped) {
            scheduleReconnect();
        }
        super.channelInactive(ctx);
    }

    /**
     * Bug-fix：exceptionCaught 也触发重连保底（channelInactive 未必总能触发）。
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.warn("[ReconnectHandler] exception on channel {}: {}, will schedule reconnect",
                ctx.channel().remoteAddress(), cause.getMessage());
        if (!reconnecting && !stopped) {
            scheduleReconnect();
        }
        super.exceptionCaught(ctx, cause);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        // 连接成功，重置重试计数与状态标志
        attempts.set(0);
        reconnecting = false;
        reconnectingSinceMs = 0;
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
        reconnectingSinceMs = System.currentTimeMillis();
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
        reconnectingSinceMs = 0;
    }

    /**
     * 重置重连状态
     */
    public void resetReconnect() {
        reconnecting = false;
        reconnectingSinceMs = 0;
        stopped = false;
        attempts.set(0);
        policy.reset();
    }
}
