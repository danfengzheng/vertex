package com.vertex.framework.socket.codec;

import com.vertex.framework.socket.core.SocketMessage;

/**
 * 消息编解码器接口
 */
public interface MessageCodec {

    /**
     * 编码：将 SocketMessage 转为字符串
     */
    String encode(SocketMessage message);

    /**
     * 解码：将字符串转为 SocketMessage
     */
    SocketMessage decode(String text);

    /**
     * 是否支持该格式
     */
    boolean supports(String text);
}
