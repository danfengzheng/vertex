package com.vertex.framework.socket.heartbeat;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.extern.slf4j.Slf4j;

/**
 * 心跳检测 Handler
 * <p>
 * 配合 IdleStateHandler 使用，当检测到读空闲时发送心跳，
 * 当检测到写空闲超过阈值时关闭连接
 */
@Slf4j
public class HeartbeatHandler extends ChannelInboundHandlerAdapter {

    private final HeartbeatStrategy heartbeatStrategy;
    private final int maxMissedHeartbeats;
    private int missedHeartbeats = 0;

    public HeartbeatHandler(HeartbeatStrategy heartbeatStrategy, int maxMissedHeartbeats) {
        this.heartbeatStrategy = heartbeatStrategy;
        this.maxMissedHeartbeats = maxMissedHeartbeats;
    }

    public HeartbeatHandler(HeartbeatStrategy heartbeatStrategy) {
        this(heartbeatStrategy, 3);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent idleEvent) {
            if (idleEvent.state() == IdleState.READER_IDLE) {
                missedHeartbeats++;
                if (missedHeartbeats >= maxMissedHeartbeats) {
                    log.warn("Missed {} heartbeats, closing connection: {}",
                            missedHeartbeats, ctx.channel().remoteAddress());
                    ctx.close();
                    return;
                }
                heartbeatStrategy.sendHeartbeat(ctx.channel());
            } else if (idleEvent.state() == IdleState.WRITER_IDLE) {
                heartbeatStrategy.sendHeartbeat(ctx.channel());
            }
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        // 收到任何消息，重置心跳计数
        missedHeartbeats = 0;
        super.channelRead(ctx, msg);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("HeartbeatHandler error on channel: {}", ctx.channel().remoteAddress(), cause);
        ctx.close();
    }
}
