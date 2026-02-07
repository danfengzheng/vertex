package com.vertex.framework.socket.codec;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import com.vertex.framework.socket.core.SocketMessage;
import lombok.extern.slf4j.Slf4j;

/**
 * JSON 消息编解码器（基于 FastJSON2）
 */
@Slf4j
public class JsonMessageCodec implements MessageCodec {

    @Override
    public String encode(SocketMessage message) {
        return JSON.toJSONString(message);
    }

    @Override
    public SocketMessage decode(String text) {
        try {
            return JSON.parseObject(text, SocketMessage.class);
        } catch (JSONException e) {
            log.warn("Failed to decode message as JSON: {}", text, e);
            return null;
        }
    }

    @Override
    public boolean supports(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String trimmed = text.trim();
        return trimmed.startsWith("{") && trimmed.endsWith("}");
    }
}
