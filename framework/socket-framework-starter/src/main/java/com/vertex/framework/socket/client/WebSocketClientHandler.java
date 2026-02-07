package com.vertex.framework.socket.client;

import com.vertex.framework.socket.core.SocketMessage;
import com.vertex.framework.socket.core.SocketSession;
import io.netty.channel.*;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.websocketx.*;
import lombok.extern.slf4j.Slf4j;

/**
 * WebSocket 客户端 Handler
 * <p>
 * 处理 WebSocket 握手完成、消息接收、连接关闭等事件
 */
@Slf4j
public class WebSocketClientHandler extends SimpleChannelInboundHandler<Object> {

    private final WebSocketClientHandshaker handshaker;
    private ChannelPromise handshakeFuture;
    private final WebSocketMessageListener messageListener;

    public WebSocketClientHandler(WebSocketClientHandshaker handshaker, WebSocketMessageListener messageListener) {
        this.handshaker = handshaker;
        this.messageListener = messageListener;
    }

    public ChannelFuture handshakeFuture() {
        return handshakeFuture;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        handshakeFuture = ctx.newPromise();
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        handshaker.handshake(ctx.channel());
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
        Channel ch = ctx.channel();

        // 握手阶段
        if (!handshaker.isHandshakeComplete()) {
            try {
                handshaker.finishHandshake(ch, (FullHttpResponse) msg);
                log.info("WebSocket handshake completed: {}", ch.remoteAddress());

                // 绑定 Session
                SocketSession session = new SocketSession(ch);
                SocketSession.bindToChannel(ch, session);

                handshakeFuture.setSuccess();
            } catch (WebSocketHandshakeException e) {
                log.error("WebSocket handshake failed", e);
                handshakeFuture.setFailure(e);
            }
            return;
        }

        // 处理 WebSocket Frame
        if (msg instanceof TextWebSocketFrame textFrame) {
            String text = textFrame.text();
            log.debug("Received text message: {}", text);
            if (messageListener != null) {
                messageListener.onMessage(SocketSession.getFromChannel(ch), text);
            }
        } else if (msg instanceof PongWebSocketFrame) {
            log.debug("Received pong from: {}", ch.remoteAddress());
        } else if (msg instanceof CloseWebSocketFrame closeFrame) {
            log.info("Received close frame, status: {}, reason: {}",
                    closeFrame.statusCode(), closeFrame.reasonText());
            ch.close();
        } else if (msg instanceof PingWebSocketFrame) {
            log.debug("Received ping, sending pong");
            ctx.writeAndFlush(new PongWebSocketFrame());
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        log.info("WebSocket client disconnected: {}", ctx.channel().remoteAddress());
        if (messageListener != null) {
            messageListener.onDisconnected(SocketSession.getFromChannel(ctx.channel()));
        }
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("WebSocket client error", cause);
        if (!handshakeFuture.isDone()) {
            handshakeFuture.setFailure(cause);
        }
        if (messageListener != null) {
            messageListener.onError(SocketSession.getFromChannel(ctx.channel()), cause);
        }
        ctx.close();
    }

    /**
     * 消息监听器接口
     */
    public interface WebSocketMessageListener {
        void onMessage(SocketSession session, String message);

        default void onDisconnected(SocketSession session) {}

        default void onError(SocketSession session, Throwable cause) {}
    }
}
