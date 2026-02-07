package com.vertex.framework.socket.pool;

import com.vertex.framework.socket.client.WebSocketClient;
import com.vertex.framework.socket.client.WebSocketClientConfig;
import com.vertex.framework.socket.client.WebSocketClientHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import java.time.Duration;

/**
 * WebSocket 连接池
 * <p>
 * 基于 commons-pool2 实现，管理 WebSocket 客户端连接的复用
 */
@Slf4j
public class SocketConnectionPool implements AutoCloseable {

    private final GenericObjectPool<WebSocketClient> pool;

    public SocketConnectionPool(WebSocketClientConfig clientConfig,
                                WebSocketClientHandler.WebSocketMessageListener messageListener,
                                ConnectionPoolConfig poolConfig) {
        GenericObjectPoolConfig<WebSocketClient> config = new GenericObjectPoolConfig<>();
        config.setMaxTotal(poolConfig.getMaxTotal());
        config.setMaxIdle(poolConfig.getMaxIdle());
        config.setMinIdle(poolConfig.getMinIdle());
        config.setMaxWait(Duration.ofMillis(poolConfig.getMaxWaitMs()));
        config.setTimeBetweenEvictionRuns(Duration.ofMillis(poolConfig.getEvictionRunIntervalMs()));
        config.setMinEvictableIdleDuration(Duration.ofMillis(poolConfig.getMinEvictableIdleTimeMs()));
        config.setTestOnBorrow(poolConfig.isTestOnBorrow());
        config.setTestOnReturn(poolConfig.isTestOnReturn());

        PooledConnectionFactory factory = new PooledConnectionFactory(clientConfig, messageListener);
        pool = new GenericObjectPool<>(factory, config);
        log.info("WebSocket connection pool created, maxTotal: {}, maxIdle: {}, minIdle: {}",
                poolConfig.getMaxTotal(), poolConfig.getMaxIdle(), poolConfig.getMinIdle());
    }

    /**
     * 借出连接
     */
    public WebSocketClient borrowClient() throws Exception {
        WebSocketClient client = pool.borrowObject();
        log.debug("Borrowed client from pool, active: {}, idle: {}", pool.getNumActive(), pool.getNumIdle());
        return client;
    }

    /**
     * 归还连接
     */
    public void returnClient(WebSocketClient client) {
        if (client != null) {
            pool.returnObject(client);
            log.debug("Returned client to pool, active: {}, idle: {}", pool.getNumActive(), pool.getNumIdle());
        }
    }

    /**
     * 废弃连接（连接异常时调用）
     */
    public void invalidateClient(WebSocketClient client) throws Exception {
        if (client != null) {
            pool.invalidateObject(client);
            log.debug("Invalidated client in pool");
        }
    }

    /**
     * 获取活跃连接数
     */
    public int getActiveCount() {
        return pool.getNumActive();
    }

    /**
     * 获取空闲连接数
     */
    public int getIdleCount() {
        return pool.getNumIdle();
    }

    @Override
    public void close() {
        pool.close();
        log.info("WebSocket connection pool closed");
    }
}
