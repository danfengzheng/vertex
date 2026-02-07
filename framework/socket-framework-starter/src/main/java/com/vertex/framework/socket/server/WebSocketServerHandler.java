package com.vertex.framework.socket.server;

import com.vertex.framework.socket.core.SocketSession;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.*;
import lombok.extern.slf4j.Slf4j;

/**
 * WebSocket 服务端消息处理 Handler
 */
@Slf4j
public class WebSocketServerHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

    private final SessionRegistry sessionRegistry;
    private final ServerMessageListener messageListener;

    public WebSocketServerHandler(SessionRegistry sessionRegistry, ServerMessageListener messageListener) {
        this.sessionRegistry = sessionRegistry;
        this.messageListener = messageListener;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        SocketSession session = new SocketSession(ctx.channel());
        SocketSession.bindToChannel(ctx.channel(), session);
        sessionRegistry.register(session);

        if (messageListener != null) {
            messageListener.onConnected(session);
        }
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        SocketSession session = SocketSession.getFromChannel(ctx.channel());
        if (session != null) {
            sessionRegistry.unregister(session.getId());
            if (messageListener != null) {
                messageListener.onDisconnected(session);
            }
        }
        super.channelInactive(ctx);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
        SocketSession session = SocketSession.getFromChannel(ctx.channel());

        if (frame instanceof TextWebSocketFrame textFrame) {
            String text = textFrame.text();
            log.debug("Received text from {}: {}", session.getId(), text);
            if (messageListener != null) {
                messageListener.onMessage(session, text);
            }
        } else if (frame instanceof PingWebSocketFrame) {
            ctx.writeAndFlush(new PongWebSocketFrame(frame.content().retain()));
        } else if (frame instanceof PongWebSocketFrame) {
            log.debug("Received pong from: {}", ctx.channel().remoteAddress());
        } else if (frame instanceof CloseWebSocketFrame closeFrame) {
            log.info("Received close from {}, status: {}", session.getId(), closeFrame.statusCode());
            ctx.close();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        SocketSession session = SocketSession.getFromChannel(ctx.channel());
        log.error("Server handler error, session: {}", session != null ? session.getId() : "unknown", cause);
        if (messageListener != null && session != null) {
            messageListener.onError(session, cause);
        }
        ctx.close();
    }

    /**
     * 服务端消息监听器
     */
    public interface ServerMessageListener {
        void onMessage(SocketSession session, String message);

        default void onConnected(SocketSession session) {}

        default void onDisconnected(SocketSession session) {}

        default void onError(SocketSession session, Throwable cause) {}
    }
}
