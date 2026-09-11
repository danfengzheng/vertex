package com.vertex.framework.socket.reconnect;

import io.netty.channel.ChannelHandlerContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * {@link ReconnectHandler} 关键行为验证。
 * <p>
 * 覆盖 2026-09-03 事故引入的三个修复：
 * <ol>
 *   <li>exceptionCaught 保底触发重连</li>
 *   <li>reconnecting 卡死 5min 强制 reset</li>
 *   <li>2 线程调度器不因单任务 hang 而全阻塞（间接验证）</li>
 * </ol>
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReconnectHandlerTest {

    @Mock ChannelHandlerContext ctx;

    ImmediatePolicy policy;
    CountingReconnectTask task;
    ReconnectHandler handler;

    @BeforeEach
    void setUp() {
        policy = new ImmediatePolicy(5);
        task = new CountingReconnectTask();
        handler = new ReconnectHandler(policy, task);
    }

    @Test
    @DisplayName("channelInactive 触发一次 scheduleReconnect")
    void channelInactive_schedulesReconnect() throws Exception {
        handler.channelInactive(ctx);
        assertTrue(task.awaitAtLeast(1, 1000L));
    }

    @Test
    @DisplayName("exceptionCaught 也能触发 scheduleReconnect（本次事故修复）")
    void exceptionCaught_alsoTriggersReconnect() throws Exception {
        handler.exceptionCaught(ctx, new RuntimeException("mock socket err"));
        assertTrue(task.awaitAtLeast(1, 1000L));
    }

    @Test
    @DisplayName("stopReconnect 后续 inactive 不再重连")
    void stopReconnect_suppressesFurtherAttempts() throws Exception {
        handler.stopReconnect();
        handler.channelInactive(ctx);
        // 等待一小段时间确认没有触发
        Thread.sleep(300);
        assertEquals(0, task.count());
    }

    @Test
    @DisplayName("reconnecting 卡死 5min 强制 reset —— 反射注入过期时间戳")
    void reconnectingStuck_forceReset() throws Exception {
        // 反射设置 reconnecting=true 且 reconnectingSinceMs=6min 前
        Field reconnectingField = ReconnectHandler.class.getDeclaredField("reconnecting");
        reconnectingField.setAccessible(true);
        reconnectingField.setBoolean(handler, true);

        Field sinceField = ReconnectHandler.class.getDeclaredField("reconnectingSinceMs");
        sinceField.setAccessible(true);
        sinceField.setLong(handler, System.currentTimeMillis() - 6 * 60_000L);

        // 触发 channelInactive —— 应检测到卡死，强制 reset 并重新调度
        handler.channelInactive(ctx);
        assertTrue(task.awaitAtLeast(1, 1000L),
                "reconnecting 卡死后 channelInactive 应能强制重新调度重连");
    }

    @Test
    @DisplayName("resetReconnect 清空所有状态")
    void resetReconnect_clearsAllFlags() throws Exception {
        Field reconnectingField = ReconnectHandler.class.getDeclaredField("reconnecting");
        reconnectingField.setAccessible(true);
        reconnectingField.setBoolean(handler, true);

        Field sinceField = ReconnectHandler.class.getDeclaredField("reconnectingSinceMs");
        sinceField.setAccessible(true);
        sinceField.setLong(handler, System.currentTimeMillis());

        handler.resetReconnect();

        assertEquals(false, reconnectingField.getBoolean(handler));
        assertEquals(0L, sinceField.getLong(handler));
    }

    // ─── 辅助 ────────────────────────────────────────────────

    /** 立即返回、无退避的 policy，方便测试 */
    static class ImmediatePolicy implements ReconnectPolicy {
        private final int maxAttempts;
        ImmediatePolicy(int maxAttempts) { this.maxAttempts = maxAttempts; }
        @Override public long nextDelay(int attempt) { return 10L; }
        @Override public boolean shouldRetry(int attempt) { return attempt <= maxAttempts; }
        @Override public void reset() { }
    }

    /** 计数 + latch 的 reconnect task */
    static class CountingReconnectTask implements Runnable {
        private final AtomicInteger count = new AtomicInteger(0);
        private final CountDownLatch latch = new CountDownLatch(1);
        @Override public void run() {
            count.incrementAndGet();
            latch.countDown();
        }
        int count() { return count.get(); }
        boolean awaitAtLeast(int n, long timeoutMs) throws InterruptedException {
            long start = System.currentTimeMillis();
            while (count.get() < n && System.currentTimeMillis() - start < timeoutMs) {
                Thread.sleep(20);
            }
            return count.get() >= n;
        }
    }
}
