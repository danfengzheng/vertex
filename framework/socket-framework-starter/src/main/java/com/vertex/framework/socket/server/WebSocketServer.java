package com.vertex.framework.socket.server;

import com.vertex.framework.socket.heartbeat.DefaultHeartbeatStrategy;
import com.vertex.framework.socket.heartbeat.HeartbeatHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

/**
 * WebSocket 服务端
 * <p>
 * 启动 Netty Server，监听端口提供 WebSocket 服务
 */
@Slf4j
public class WebSocketServer {

    private final WebSocketServerConfig config;
    private final WebSocketServerHandler.ServerMessageListener messageListener;

    @Getter
    private final SessionRegistry sessionRegistry;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public WebSocketServer(WebSocketServerConfig config, WebSocketServerHandler.ServerMessageListener messageListener) {
        this.config = config;
        this.messageListener = messageListener;
        this.sessionRegistry = new SessionRegistry();
    }

    /**
     * 启动服务端
     */
    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(config.getBossThreads());
        workerGroup = new NioEventLoopGroup(config.getWorkerThreads());

        int heartbeatInterval = config.getHeartbeatIntervalSeconds();

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 1024)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();

                        // HTTP 编解码
                        pipeline.addLast(new HttpServerCodec());
                        pipeline.addLast(new HttpObjectAggregator(config.getMaxFrameSize()));

                        // WebSocket 协议处理
                        pipeline.addLast(new WebSocketServerProtocolHandler(
                                config.getPath(), null, true, config.getMaxFrameSize()));

                        // 心跳检测
                        if (heartbeatInterval > 0) {
                            pipeline.addLast(new IdleStateHandler(
                                    heartbeatInterval, 0, 0, TimeUnit.SECONDS));
                            pipeline.addLast(new HeartbeatHandler(new DefaultHeartbeatStrategy()));
                        }

                        // 业务处理
                        pipeline.addLast(new WebSocketServerHandler(sessionRegistry, messageListener));
                    }
                });

        ChannelFuture future = bootstrap.bind(config.getPort()).sync();
        serverChannel = future.channel();
        log.info("WebSocket server started on port {} with path {}", config.getPort(), config.getPath());
    }

    /**
     * 停止服务端
     */
    public void stop() {
        log.info("Stopping WebSocket server...");
        sessionRegistry.closeAll();
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        log.info("WebSocket server stopped");
    }

    /**
     * 广播消息
     */
    public void broadcast(String message) {
        sessionRegistry.broadcast(message);
    }
}
