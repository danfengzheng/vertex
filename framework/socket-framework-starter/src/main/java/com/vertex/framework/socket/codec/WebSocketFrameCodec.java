package com.vertex.framework.socket.codec;

import com.vertex.framework.socket.core.SocketMessage;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageCodec;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Netty WebSocketFrame 与 SocketMessage 之间的转换 Handler
 * <p>
 * 入站：TextWebSocketFrame → SocketMessage
 * 出站：SocketMessage → TextWebSocketFrame
 */
@Slf4j
public class WebSocketFrameCodec extends MessageToMessageCodec<TextWebSocketFrame, SocketMessage> {

    private final MessageCodec messageCodec;

    public WebSocketFrameCodec(MessageCodec messageCodec) {
        this.messageCodec = messageCodec;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, SocketMessage msg, List<Object> out) {
        String text = messageCodec.encode(msg);
        out.add(new TextWebSocketFrame(text));
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, TextWebSocketFrame frame, List<Object> out) {
        String text = frame.text();
        if (messageCodec.supports(text)) {
            SocketMessage message = messageCodec.decode(text);
            if (message != null) {
                out.add(message);
                return;
            }
        }
        // 无法解码时，构造一个 DATA 类型消息，原始文本作为 payload
        out.add(SocketMessage.data(null, text));
    }
}
