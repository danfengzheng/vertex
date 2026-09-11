package com.vertex.framework.socket.heartbeat;

import io.netty.channel.ChannelHandlerContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link HeartbeatHandler} 关键行为验证。
 * <p>
 * 覆盖 2026-09-03 事故核心修复：exceptionCaught 必须 fireExceptionCaught 传给下游，
 * 否则 ReconnectHandler 收不到异常事件，重连机制彻底失效。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HeartbeatHandlerTest {

    @Mock ChannelHandlerContext ctx;
    @Mock HeartbeatStrategy strategy;

    @Test
    @DisplayName("exceptionCaught 必须 fireExceptionCaught（本次事故核心）")
    void exceptionCaught_mustPropagateToDownstream() {
        HeartbeatHandler handler = new HeartbeatHandler(strategy);
        RuntimeException cause = new RuntimeException("Connection reset");

        handler.exceptionCaught(ctx, cause);

        verify(ctx, times(1)).fireExceptionCaught(eq(cause));   // ← 必须传给下游
        verify(ctx, times(1)).close();                          // ← 也要关连接
    }

    @Test
    @DisplayName("exceptionCaught 即使 fireExceptionCaught 抛异常仍要 close")
    void exceptionCaught_closesEvenIfFireFails() {
        HeartbeatHandler handler = new HeartbeatHandler(strategy);
        RuntimeException cause = new RuntimeException("Connection reset");
        org.mockito.Mockito.doThrow(new RuntimeException("mock fire error"))
                .when(ctx).fireExceptionCaught(cause);

        handler.exceptionCaught(ctx, cause);   // 不应抛

        verify(ctx, times(1)).close();
    }
}
