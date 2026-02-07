# Socket Framework Starter

基于 Netty 的 WebSocket 通信框架，作为 Spring Boot Starter 提供自动装配能力。用于**模块间长连接通信**以及**对接外部交易所 WebSocket API**（币安、OKX 等）。

## 技术栈

| 组件 | 版本 |
|------|------|
| Netty | 4.1.104.Final |
| Spring Boot | 3.2.1 |
| JDK | 21 |
| FastJSON2 | 2.0.45 |
| Commons Pool2 | 2.12.0 |

## 模块结构

```
com.vertex.framework.socket
├── autoconfigure/     Spring Boot 自动配置
├── client/            WebSocket 客户端（连接外部服务）
├── server/            WebSocket 服务端（提供内部 WebSocket 服务）
├── core/              核心模型：会话、消息、状态枚举
├── codec/             消息编解码（JSON 默认实现，可扩展）
├── heartbeat/         心跳检测（策略接口，适配不同协议）
├── reconnect/         自动重连（指数退避策略）
├── subscription/      订阅管理（topic → listener 分发）
├── pool/              连接池（基于 commons-pool2）
├── event/             事件体系（桥接到 Spring ApplicationEvent）
└── exchange/          交易所对接抽象层（模板方法模式）
```

## 核心能力一览

| 能力 | 说明 |
|------|------|
| 连接管理 | 客户端/服务端完整生命周期管理（connect / disconnect / reconnect） |
| 消息编解码 | `MessageCodec` 接口 + JSON 默认实现，可自定义替换 |
| 心跳检测 | 基于 `IdleStateHandler`，支持自定义心跳策略（Ping/Pong Frame、文本心跳等） |
| 自动重连 | 指数退避策略，可配置初始延迟、最大延迟、退避倍数、最大重试次数 |
| 订阅管理 | 维护 topic → listener 映射，支持多个 listener 订阅同一 topic |
| 连接池 | 基于 commons-pool2 的连接复用，支持借出/归还/废弃 |
| 事件体系 | 连接事件、消息事件桥接到 Spring `@EventListener` |
| 交易所抽象 | 模板方法模式，实现 4 个方法即可对接一个交易所 |

---

## 引入方式

在目标模块的 `build.gradle` 中添加依赖：

```gradle
dependencies {
    implementation project(':framework:socket-framework-starter')
}
```

---

## 配置说明

在 `application.yml` 中添加配置：

```yaml
vertex:
  socket:
    # ===== 服务端配置（内部模块间通信）=====
    server:
      enabled: false              # 是否启用 WebSocket 服务端
      port: 9090                  # 监听端口
      path: /ws                   # WebSocket 路径
      boss-threads: 1             # Netty Boss 线程数
      worker-threads: 4           # Netty Worker 线程数
      max-frame-size: 65536       # 最大帧大小（字节）
      heartbeat-interval-seconds: 60  # 心跳间隔（秒）

    # ===== 客户端配置 =====
    client:
      enabled: false              # 是否启用客户端自动配置
      heartbeat-interval: 30      # 心跳间隔（秒）
      max-missed-heartbeats: 3    # 最大丢失心跳次数，超过则断开
      reconnect:
        enabled: true             # 是否启用自动重连
        initial-delay: 1000       # 初始重连延迟（毫秒）
        max-delay: 60000          # 最大重连延迟（毫秒）
        multiplier: 2.0           # 退避倍数
        max-attempts: -1          # 最大重试次数，-1 表示无限重试

    # ===== 连接池配置 =====
    pool:
      max-total: 20               # 最大连接数
      max-idle: 10                # 最大空闲连接数
      min-idle: 2                 # 最小空闲连接数
      max-wait-ms: 5000           # 获取连接最大等待时间（毫秒）
```

---

## 使用指南

### 1. 作为 WebSocket 服务端（内部模块间通信）

**开启服务端：**

```yaml
vertex:
  socket:
    server:
      enabled: true
      port: 9090
      path: /ws
```

**实现消息监听器：**

```java
@Component
public class MyServerMessageListener implements WebSocketServerHandler.ServerMessageListener {

    @Override
    public void onConnected(SocketSession session) {
        System.out.println("客户端连接: " + session.getId());
    }

    @Override
    public void onMessage(SocketSession session, String message) {
        System.out.println("收到消息: " + message);
        // 回复消息
        session.send("ACK: " + message);
    }

    @Override
    public void onDisconnected(SocketSession session) {
        System.out.println("客户端断开: " + session.getId());
    }
}
```

**广播消息：**

```java
@Autowired
private WebSocketServer webSocketServer;

public void broadcastPrice(String data) {
    webSocketServer.broadcast(data);
}
```

### 2. 作为 WebSocket 客户端（连接外部服务）

```java
WebSocketClientConfig config = WebSocketClientConfig.builder()
        .uri(new URI("wss://example.com/ws"))
        .heartbeatIntervalSeconds(30)
        .autoReconnect(true)
        .build();

WebSocketClient client = new WebSocketClient(config, new WebSocketClientHandler.WebSocketMessageListener() {
    @Override
    public void onMessage(SocketSession session, String message) {
        System.out.println("收到: " + message);
    }

    @Override
    public void onDisconnected(SocketSession session) {
        System.out.println("断开连接");
    }
});

client.connect();
client.send("{\"action\":\"subscribe\",\"channel\":\"ticker\"}");
```

### 3. 使用 Spring 事件监听

```java
@Component
public class SocketEventHandler {

    @EventListener
    public void onConnection(ConnectionEvent event) {
        System.out.println("连接事件: session=" + event.getSessionId()
                + ", state=" + event.getState());
    }

    @EventListener
    public void onMessage(MessageEvent event) {
        System.out.println("消息事件: topic=" + event.getTopic()
                + ", payload=" + event.getPayload());
    }
}
```

### 4. 使用连接池

```java
// 创建连接池
SocketConnectionPool pool = new SocketConnectionPool(clientConfig, messageListener,
        ConnectionPoolConfig.builder()
                .maxTotal(20)
                .maxIdle(10)
                .minIdle(2)
                .build());

// 借出 → 使用 → 归还
WebSocketClient client = pool.borrowClient();
try {
    client.send("hello");
} finally {
    pool.returnClient(client);
}
```

---

## 交易所对接

### 架构说明

框架提供了 `ExchangeWebSocketClient` 抽象类，采用**模板方法模式**。对接一个新交易所只需继承该类并实现 4 个方法：

| 方法 | 用途 |
|------|------|
| `buildSubscribeMessage(topic, params)` | 构建交易所订阅协议的 JSON 消息 |
| `buildUnsubscribeMessage(topic)` | 构建取消订阅消息 |
| `parseMessage(rawMessage)` | 解析交易所推送的原始消息，提取 topic 和 payload |
| `createHeartbeatStrategy()` | 创建交易所特定的心跳策略 |

### 对接示例：币安

```java
public class BinanceWebSocketClient extends ExchangeWebSocketClient {

    public BinanceWebSocketClient(ExchangeConfig config) {
        super(config);
    }

    @Override
    protected String buildSubscribeMessage(String topic, Map<String, String> params) {
        // 币安订阅协议
        return JSON.toJSONString(Map.of(
                "method", "SUBSCRIBE",
                "params", List.of(topic),
                "id", System.currentTimeMillis()
        ));
    }

    @Override
    protected String buildUnsubscribeMessage(String topic) {
        return JSON.toJSONString(Map.of(
                "method", "UNSUBSCRIBE",
                "params", List.of(topic),
                "id", System.currentTimeMillis()
        ));
    }

    @Override
    protected ParsedMessage parseMessage(String rawMessage) {
        JSONObject json = JSON.parseObject(rawMessage);
        String stream = json.getString("stream");
        String data = json.getString("data");
        return new ParsedMessage(stream, data);
    }

    @Override
    protected HeartbeatStrategy createHeartbeatStrategy() {
        // 币安使用标准 WebSocket Ping/Pong
        return new DefaultHeartbeatStrategy();
    }
}
```

### 对接示例：OKX

```java
public class OkxWebSocketClient extends ExchangeWebSocketClient {

    public OkxWebSocketClient(ExchangeConfig config) {
        super(config);
    }

    @Override
    protected String buildSubscribeMessage(String topic, Map<String, String> params) {
        // OKX 订阅协议
        JSONObject arg = new JSONObject();
        arg.put("channel", topic);
        if (params != null) {
            arg.put("instId", params.get("instId"));
        }
        return JSON.toJSONString(Map.of(
                "op", "subscribe",
                "args", List.of(arg)
        ));
    }

    @Override
    protected String buildUnsubscribeMessage(String topic) {
        return JSON.toJSONString(Map.of(
                "op", "unsubscribe",
                "args", List.of(Map.of("channel", topic))
        ));
    }

    @Override
    protected ParsedMessage parseMessage(String rawMessage) {
        JSONObject json = JSON.parseObject(rawMessage);
        JSONObject arg = json.getJSONObject("arg");
        if (arg != null) {
            String channel = arg.getString("channel");
            String data = json.getString("data");
            return new ParsedMessage(channel, data);
        }
        return null;
    }

    @Override
    protected HeartbeatStrategy createHeartbeatStrategy() {
        // OKX 需要发送 "ping" 文本，回复 "pong" 文本
        return new HeartbeatStrategy() {
            @Override
            public void sendHeartbeat(Channel channel) {
                channel.writeAndFlush(new TextWebSocketFrame("ping"));
            }

            @Override
            public boolean isHeartbeatResponse(String message) {
                return "pong".equals(message);
            }

            @Override
            public void handleHeartbeatResponse(Channel channel, String message) {
                // OKX pong 无需额外处理
            }
        };
    }
}
```

---

## 多交易所同时订阅

**支持同时订阅多个交易所的数据。** 每个 `ExchangeWebSocketClient` 实例是独立的，拥有独立的：

- WebSocket 连接（独立的 Netty Channel）
- 订阅管理器（独立的 `SubscriptionManager`）
- 心跳策略和重连策略
- 事件循环线程组

因此可以创建多个实例，分别连接不同交易所，互不干扰。

### 多交易所使用示例

```java
@Configuration
public class ExchangeClientConfiguration {

    @Bean
    public BinanceWebSocketClient binanceClient() {
        return new BinanceWebSocketClient(ExchangeConfig.builder()
                .exchangeType(ExchangeType.BINANCE)
                .wsUrl("wss://stream.binance.com:9443/ws")
                .heartbeatIntervalSeconds(20)
                .autoReconnect(true)
                .build());
    }

    @Bean
    public OkxWebSocketClient okxClient() {
        return new OkxWebSocketClient(ExchangeConfig.builder()
                .exchangeType(ExchangeType.OKX)
                .wsUrl("wss://ws.okx.com:8443/ws/v5/public")
                .heartbeatIntervalSeconds(25)
                .autoReconnect(true)
                .build());
    }
}
```

```java
@Service
public class MarketDataService {

    @Autowired
    private BinanceWebSocketClient binanceClient;

    @Autowired
    private OkxWebSocketClient okxClient;

    @PostConstruct
    public void init() throws Exception {
        // 同时连接两个交易所
        binanceClient.connect();
        okxClient.connect();

        // 币安：订阅 BTC/USDT 行情
        binanceClient.subscribe("btcusdt@ticker", (topic, payload) -> {
            System.out.println("[币安] BTC行情: " + payload);
        });

        // 币安：订阅 ETH/USDT 深度
        binanceClient.subscribe("ethusdt@depth", (topic, payload) -> {
            System.out.println("[币安] ETH深度: " + payload);
        });

        // OKX：订阅 BTC/USDT 行情
        okxClient.subscribe("tickers", Map.of("instId", "BTC-USDT"), (topic, payload) -> {
            System.out.println("[OKX] BTC行情: " + payload);
        });

        // OKX：订阅 ETH/USDT K线
        okxClient.subscribe("candle1m", Map.of("instId", "ETH-USDT"), (topic, payload) -> {
            System.out.println("[OKX] ETH K线: " + payload);
        });
    }

    @PreDestroy
    public void destroy() {
        binanceClient.disconnect();
        okxClient.disconnect();
    }
}
```

### 多交易所数据聚合

```java
@Service
public class PriceAggregator {

    private final Map<String, Map<String, String>> latestPrices = new ConcurrentHashMap<>();

    @Autowired
    private BinanceWebSocketClient binanceClient;

    @Autowired
    private OkxWebSocketClient okxClient;

    @PostConstruct
    public void start() throws Exception {
        binanceClient.connect();
        okxClient.connect();

        // 同一币种从多个交易所获取价格，进行聚合对比
        binanceClient.subscribe("btcusdt@ticker", (topic, payload) -> {
            latestPrices.computeIfAbsent("BTC-USDT", k -> new ConcurrentHashMap<>())
                    .put("binance", payload);
        });

        okxClient.subscribe("tickers", Map.of("instId", "BTC-USDT"), (topic, payload) -> {
            latestPrices.computeIfAbsent("BTC-USDT", k -> new ConcurrentHashMap<>())
                    .put("okx", payload);
        });
    }

    /**
     * 获取某币种在所有交易所的最新价格
     */
    public Map<String, String> getPrices(String symbol) {
        return latestPrices.getOrDefault(symbol, Map.of());
    }
}
```

---

## 自定义扩展点

框架通过 `@ConditionalOnMissingBean` 实现扩展，自定义 Bean 会覆盖默认实现：

| 扩展点 | 默认实现 | 说明 |
|--------|---------|------|
| `MessageCodec` | `JsonMessageCodec` | 消息编解码器 |
| `HeartbeatStrategy` | `DefaultHeartbeatStrategy` | 心跳策略（标准 Ping/Pong） |
| `ReconnectPolicy` | `ExponentialBackoffPolicy` | 重连策略 |
| `SubscriptionManager` | 默认实例 | 订阅管理器 |
| `SocketEventPublisher` | 默认实例 | 事件发布器 |
| `ServerMessageListener` | 无（需用户提供） | 服务端消息处理 |

### 自定义编解码器示例

```java
@Bean
public MessageCodec messageCodec() {
    return new MessageCodec() {
        @Override
        public String encode(SocketMessage message) {
            // 自定义序列化
        }

        @Override
        public SocketMessage decode(String text) {
            // 自定义反序列化
        }

        @Override
        public boolean supports(String text) {
            return true;
        }
    };
}
```

---

## 关键类速查

| 类名 | 路径 | 用途 |
|------|------|------|
| `WebSocketClient` | `client/` | 客户端核心，管理连接生命周期 |
| `WebSocketServer` | `server/` | 服务端核心，启动 Netty Server |
| `SocketSession` | `core/` | 会话封装，提供 send/close/attribute |
| `SocketMessage` | `core/` | 统一消息模型 |
| `HeartbeatStrategy` | `heartbeat/` | 心跳策略接口 |
| `ReconnectPolicy` | `reconnect/` | 重连策略接口 |
| `SubscriptionManager` | `subscription/` | 订阅管理，topic → listener 分发 |
| `SocketConnectionPool` | `pool/` | 连接池管理 |
| `ExchangeWebSocketClient` | `exchange/` | 交易所对接抽象基类 |
| `ExchangeConfig` | `exchange/` | 交易所连接配置 |
| `SocketAutoConfiguration` | `autoconfigure/` | 自动配置入口 |
| `SocketProperties` | `autoconfigure/` | **配置属性绑定** |
