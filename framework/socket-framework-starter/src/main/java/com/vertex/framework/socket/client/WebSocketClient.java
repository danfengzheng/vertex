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
     * <p>
     * <b>Bug-fix（线上事故）</b>：本方法必须保证「失败时状态完全回滚」，否则会导致：
     * <ul>
     *   <li>state 卡在 CONNECTING → 后续 connect() 全被开头守卫拦截，永久无法重连</li>
     *   <li>group 已 new 但未 shutdown → NioEventLoopGroup 线程泄漏</li>
     * </ul>
     * 修复：整个连接过程包入 try/catch，任何阶段失败都回滚 state、shutdown group、清引用、
     * 再 rethrow 让上层（ReconnectHandler）感知并触发下一轮重连。
     * </p>
     */
    public synchronized void connect() throws Exception {
        if (state == SocketConnectionState.CONNECTED || state == SocketConnectionState.CONNECTING) {
            log.warn("Already connected or connecting, skip");
            return;
        }

        state = SocketConnectionState.CONNECTING;
        NioEventLoopGroup newGroup = null;
        Channel newChannel = null;
        try {
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

            // 包装 listener：在 IO 线程调用 onConnected() 之前，先将 state/session 迁移完毕。
            // 否则 onConnectionReady() → resubscribeAll() → sendMessage() → isConnected() 会因
            // state 仍为 RECONNECTING 而返回 false，导致重连后订阅消息被静默丢弃。
            WebSocketClientHandler.WebSocketMessageListener wrappedListener =
                    new WebSocketClientHandler.WebSocketMessageListener() {
                @Override
                public void onConnected(SocketSession s) {
                    state = SocketConnectionState.CONNECTED;
                    session = s;
                    messageListener.onConnected(s);
                }
                @Override
                public void onMessage(SocketSession s, String message) {
                    messageListener.onMessage(s, message);
                }
                @Override
                public void onDisconnected(SocketSession s) {
                    messageListener.onDisconnected(s);
                }
                @Override
                public void onError(SocketSession s, Throwable cause) {
                    messageListener.onError(s, cause);
                }
            };
            WebSocketClientHandler clientHandler = new WebSocketClientHandler(handshaker, wrappedListener);

            // 获取策略（使用默认值 fallback）
            HeartbeatStrategy heartbeatStrategy = config.getHeartbeatStrategy() != null
                    ? config.getHeartbeatStrategy() : new DefaultHeartbeatStrategy();
            ReconnectPolicy reconnectPolicy = config.getReconnectPolicy() != null
                    ? config.getReconnectPolicy() : new ExponentialBackoffPolicy();

            int heartbeatInterval = config.getHeartbeatIntervalSeconds();
            boolean autoReconnect = config.isAutoReconnect();
            int finalPort = port;

            newGroup = new NioEventLoopGroup(config.getWorkerThreads());
            group = newGroup;
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(newGroup)
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
                            // Bug-fix：reconnectTask 内部必须把异常向上抛，否则 ReconnectHandler 的
                            //          outer catch 不会触发，导致 scheduleReconnect 不会被再次调用，
                            //          重连机制实质上停止工作（曾导致线上 8 小时断连无法自愈）。
                            if (autoReconnect) {
                                pipeline.addLast(new ReconnectHandler(reconnectPolicy, () -> {
                                    try {
                                        reconnect();
                                    } catch (Exception e) {
                                        log.error("Reconnect failed", e);
                                        // 关键：rethrow 让 ReconnectHandler.scheduleReconnect 的
                                        // outer catch 感知失败并调度下一次重连
                                        throw new RuntimeException("Reconnect failed", e);
                                    }
                                }));
                            }

                            // WebSocket 客户端 Handler
                            pipeline.addLast(clientHandler);
                        }
                    });

            ChannelFuture future = bootstrap.connect(host, finalPort).sync();
            newChannel = future.channel();
            channel = newChannel;

            // 等待握手完成（可能抛 WebSocketHandshakeException / 超时等）
            clientHandler.handshakeFuture().sync();
            state = SocketConnectionState.CONNECTED;
            session = SocketSession.getFromChannel(newChannel);
            log.info("WebSocket client connected to: {}", uri);
        } catch (Exception e) {
            // 回滚：任何阶段失败都必须把状态清回 DISCONNECTED，释放本次申请的 group，
            // 否则 state 卡在 CONNECTING + group 泄漏 → 永久无法重连（线上事故根因）。
            log.error("WebSocket connect failed, rolling back state: {}", e.getMessage());
            try {
                if (newChannel != null && newChannel.isOpen()) {
                    newChannel.close();
                }
            } catch (Exception closeEx) {
                log.warn("Failed to close channel during rollback: {}", closeEx.getMessage());
            }
            try {
                if (newGroup != null) {
                    newGroup.shutdownGracefully();
                }
            } catch (Exception shutEx) {
                log.warn("Failed to shutdown group during rollback: {}", shutEx.getMessage());
            }
            channel = null;
            group = null;
            session = null;
            state = SocketConnectionState.DISCONNECTED;
            throw e;
        }
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
     * 重连。
     * <p>
     * <b>synchronized 防止并发</b>：心跳超时、底层 channelInactive、上层手动 reconnect
     * 等多路径可能同时触发本方法，未加锁时会出现「A 已连上后 B 又把它关掉再连一次」的状态机抖动。
     * disconnect() 与 connect() 各自 synchronized，但整个 reconnect 序列必须原子。
     * </p>
     */
    public synchronized void reconnect() throws Exception {
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
