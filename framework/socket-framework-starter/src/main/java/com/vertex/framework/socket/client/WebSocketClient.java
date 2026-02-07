package com.vertex.framework.socket.client;

import com.vertex.framework.socket.codec.JsonMessageCodec;
import com.vertex.framework.socket.codec.MessageCodec;
import com.vertex.framework.socket.core.SocketConnectionState;
import com.vertex.framework.socket.core.SocketSession;
import com.vertex.framework.socket.heartbeat.DefaultHeartbeatStrategy;
import com.vertex.framework.socket.heartbeat.HeartbeatHandler;
import com.vertex.framework.socket.heartbeat.HeartbeatStrategy;
import com.vertex.framework.socket.reconnect.ExponentialBackoffPolicy;
import com.vertex.framework.socket.reconnect.ReconnectHandler;
import com.vertex.framework.socket.reconnect.ReconnectPolicy;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket 客户端
 * <p>
 * 核心类，管理连接生命周期（connect/disconnect/reconnect）
 */
@Slf4j
public class WebSocketClient {

    private final WebSocketClientConfig config;
    private final WebSocketClientHandler.WebSocketMessageListener messageListener;

    private EventLoopGroup group;
    private Channel channel;

    @Getter
    private volatile SocketConnectionState state = SocketConnectionState.DISCONNECTED;

    @Getter
    private SocketSession session;

    public WebSocketClient(WebSocketClientConfig config, WebSocketClientHandler.WebSocketMessageListener messageListener) {
        this.config = config;
        this.messageListener = messageListener;
    }

    /**
     * 连接到 WebSocket 服务
     */
    public synchronized void connect() throws Exception {
        if (state == SocketConnectionState.CONNECTED || state == SocketConnectionState.CONNECTING) {
            log.warn("Already connected or connecting, skip");
            return;
        }

        state = SocketConnectionState.CONNECTING;
        URI uri = config.getUri();
        String scheme = uri.getScheme() == null ? "ws" : uri.getScheme();
        String host = uri.getHost();
        int port = uri.getPort();
        if (port == -1) {
            port = "wss".equalsIgnoreCase(scheme) ? 443 : 80;
        }

        boolean ssl = "wss".equalsIgnoreCase(scheme);
        final SslContext sslCtx;
        if (ssl) {
            sslCtx = SslContextBuilder.forClient()
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .build();
        } else {
            sslCtx = null;
        }

        // 构建 HTTP Headers
        HttpHeaders httpHeaders = new DefaultHttpHeaders();
        if (config.getHeaders() != null) {
            config.getHeaders().forEach(httpHeaders::add);
        }

        // 构建握手器
        var handshaker = WebSocketClientHandshakerFactory.newHandshaker(
                uri, WebSocketVersion.V13, null, true, httpHeaders, config.getMaxFrameSize());

        WebSocketClientHandler clientHandler = new WebSocketClientHandler(handshaker, messageListener);

        // 获取策略（使用默认值 fallback）
        HeartbeatStrategy heartbeatStrategy = config.getHeartbeatStrategy() != null
                ? config.getHeartbeatStrategy() : new DefaultHeartbeatStrategy();
        ReconnectPolicy reconnectPolicy = config.getReconnectPolicy() != null
                ? config.getReconnectPolicy() : new ExponentialBackoffPolicy();

        int heartbeatInterval = config.getHeartbeatIntervalSeconds();
        boolean autoReconnect = config.isAutoReconnect();
        int finalPort = port;

        group = new NioEventLoopGroup(config.getWorkerThreads());
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.getConnectTimeoutMs())
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();

                        // SSL
                        if (sslCtx != null) {
                            pipeline.addLast(sslCtx.newHandler(ch.alloc(), host, finalPort));
                        }

                        // HTTP 编解码
                        pipeline.addLast(new HttpClientCodec());
                        pipeline.addLast(new HttpObjectAggregator(config.getMaxFrameSize()));

                        // 心跳检测
                        if (heartbeatInterval > 0) {
                            pipeline.addLast(new IdleStateHandler(
                                    heartbeatInterval * 2, heartbeatInterval, 0, TimeUnit.SECONDS));
                            pipeline.addLast(new HeartbeatHandler(heartbeatStrategy, config.getMaxMissedHeartbeats()));
                        }

                        // 自动重连
                        if (autoReconnect) {
                            pipeline.addLast(new ReconnectHandler(reconnectPolicy, () -> {
                                try {
                                    reconnect();
                                } catch (Exception e) {
                                    log.error("Reconnect failed", e);
                                }
                            }));
                        }

                        // WebSocket 客户端 Handler
                        pipeline.addLast(clientHandler);
                    }
                });

        ChannelFuture future = bootstrap.connect(host, finalPort).sync();
        channel = future.channel();

        // 等待握手完成
        clientHandler.handshakeFuture().sync();
        state = SocketConnectionState.CONNECTED;
        session = SocketSession.getFromChannel(channel);
        log.info("WebSocket client connected to: {}", uri);
    }

    /**
     * 断开连接
     */
    public synchronized void disconnect() {
        if (state == SocketConnectionState.DISCONNECTED) {
            return;
        }
        state = SocketConnectionState.DISCONNECTING;
        try {
            if (channel != null && channel.isActive()) {
                channel.close().sync();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while closing channel", e);
        } finally {
            if (group != null) {
                group.shutdownGracefully();
            }
            state = SocketConnectionState.DISCONNECTED;
            session = null;
            log.info("WebSocket client disconnected");
        }
    }

    /**
     * 重连
     */
    public void reconnect() throws Exception {
        state = SocketConnectionState.RECONNECTING;
        log.info("Reconnecting to: {}", config.getUri());
        disconnect();
        connect();
    }

    /**
     * 发送文本消息
     */
    public void send(String text) {
        if (session != null && session.isActive()) {
            session.send(text);
        } else {
            log.warn("Cannot send message, session is not active");
        }
    }

    /**
     * 是否已连接
     */
    public boolean isConnected() {
        return state == SocketConnectionState.CONNECTED && channel != null && channel.isActive();
    }
}
