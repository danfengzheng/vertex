package com.vertex.framework.socket.pool;

import com.vertex.framework.socket.client.WebSocketClient;
import com.vertex.framework.socket.client.WebSocketClientConfig;
import com.vertex.framework.socket.client.WebSocketClientHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;

/**
 * 池化 WebSocket 连接工厂
 */
@Slf4j
public class PooledConnectionFactory extends BasePooledObjectFactory<WebSocketClient> {

    private final WebSocketClientConfig config;
    private final WebSocketClientHandler.WebSocketMessageListener messageListener;

    public PooledConnectionFactory(WebSocketClientConfig config,
                                   WebSocketClientHandler.WebSocketMessageListener messageListener) {
        this.config = config;
        this.messageListener = messageListener;
    }

    @Override
    public WebSocketClient create() throws Exception {
        WebSocketClient client = new WebSocketClient(config, messageListener);
        client.connect();
        log.debug("Created new pooled WebSocket connection");
        return client;
    }

    @Override
    public PooledObject<WebSocketClient> wrap(WebSocketClient client) {
        return new DefaultPooledObject<>(client);
    }

    @Override
    public void destroyObject(PooledObject<WebSocketClient> p) {
        WebSocketClient client = p.getObject();
        client.disconnect();
        log.debug("Destroyed pooled WebSocket connection");
    }

    @Override
    public boolean validateObject(PooledObject<WebSocketClient> p) {
        return p.getObject().isConnected();
    }
}
