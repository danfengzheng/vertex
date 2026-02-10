# Vertex 策略模块使用手册

> 版本：1.0 | 适用于 Vertex 策略引擎

---

## 目录

- [1. 架构概览](#1-架构概览)
  - [1.1 系统组件](#11-系统组件)
  - [1.2 数据流](#12-数据流)
  - [1.3 技术栈](#13-技术栈)
- [2. 快速开始](#2-快速开始)
  - [2.1 环境准备](#21-环境准备)
  - [2.2 启动服务](#22-启动服务)
  - [2.3 创建第一个策略](#23-创建第一个策略)
- [3. API 参考](#3-api-参考)
  - [3.1 策略管理](#31-策略管理)
  - [3.2 信号管理](#32-信号管理)
  - [3.3 策略回测](#33-策略回测)
- [4. 技术指标参考](#4-技术指标参考)
  - [4.1 MA - 简单移动平均线](#41-ma---简单移动平均线)
  - [4.2 EMA - 指数移动平均线](#42-ema---指数移动平均线)
  - [4.3 RSI - 相对强弱指数](#43-rsi---相对强弱指数)
  - [4.4 MACD - 移动平均收敛散度](#44-macd---移动平均收敛散度)
  - [4.5 BOLL - 布林带](#45-boll---布林带)
  - [4.6 KDJ - 随机指标](#46-kdj---随机指标)
  - [4.7 ATR - 平均真实波幅](#47-atr---平均真实波幅)
- [5. 开发者指南](#5-开发者指南)
  - [5.1 项目结构](#51-项目结构)
  - [5.2 添加自定义指标](#52-添加自定义指标)
  - [5.3 信号权重聚合机制](#53-信号权重聚合机制)
- [6. 策略配置教程](#6-策略配置教程)
  - [6.1 创建策略](#61-创建策略)
  - [6.2 指标参数配置](#62-指标参数配置)
  - [6.3 权重系统](#63-权重系统)
  - [6.4 启用与禁用](#64-启用与禁用)
- [7. 回测教程](#7-回测教程)
  - [7.1 执行回测](#71-执行回测)
  - [7.2 回测参数说明](#72-回测参数说明)
  - [7.3 结果指标解读](#73-结果指标解读)
  - [7.4 资金曲线与交易记录](#74-资金曲线与交易记录)
- [8. 信号监控教程](#8-信号监控教程)
  - [8.1 实时推送原理](#81-实时推送原理)
  - [8.2 信号列表与筛选](#82-信号列表与筛选)
  - [8.3 手动分析触发](#83-手动分析触发)
  - [8.4 信号详情查看](#84-信号详情查看)
- [9. 配置参考](#9-配置参考)
  - [9.1 后端配置项](#91-后端配置项)
  - [9.2 WebSocket 配置](#92-websocket-配置)
  - [9.3 数据库表结构](#93-数据库表结构)

---

## 1. 架构概览

### 1.1 系统组件

Vertex 策略模块是一套完整的量化交易信号生成系统，由以下核心组件构成：

```
┌──────────────────────────────────────────────────────────────┐
│                        前端 (React)                          │
│  ┌──────────────┐  ┌──────────────┐  ┌────────────────────┐ │
│  │  策略配置页   │  │  信号监控页   │  │    回测面板        │ │
│  │ StrategyConfig│  │SignalMonitor │  │  BacktestPanel    │ │
│  └──────────────┘  └──────┬───────┘  └────────────────────┘ │
│                           │ WebSocket                        │
└───────────────────────────┼──────────────────────────────────┘
                            │
┌───────────────────────────┼──────────────────────────────────┐
│                     后端 (Spring Boot)                        │
│                           │                                   │
│  ┌────────────────────────▼──────────────────────────────┐   │
│  │                  WebSocket 推送层                       │   │
│  │              SignalPushService (STOMP)                  │   │
│  └────────────────────────▲──────────────────────────────┘   │
│                           │                                   │
│  ┌────────────────────────┼──────────────────────────────┐   │
│  │                    策略引擎层                           │   │
│  │  StrategyEventListener → StrategyEngineService         │   │
│  │                              ↓                         │   │
│  │                       SignalGenerator                   │   │
│  │              ┌────────────┼────────────┐               │   │
│  │              ▼            ▼            ▼               │   │
│  │           MA/EMA      RSI/MACD    BOLL/KDJ/ATR        │   │
│  │         (指标计算层 - IndicatorRegistry)                │   │
│  └───────────────────────────────────────────────────────┘   │
│                           │                                   │
│  ┌────────────────────────▼──────────────────────────────┐   │
│  │                    数据存储层                           │   │
│  │         MySQL (MyBatis Plus)  +  RocksDB               │   │
│  └───────────────────────────────────────────────────────┘   │
│                           ▲                                   │
│  ┌────────────────────────┼──────────────────────────────┐   │
│  │                   行情数据层                            │   │
│  │           KLineStore (K线存储与查询)                    │   │
│  └───────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────┘
```

**核心组件说明：**

| 组件 | 职责 |
|------|------|
| **IndicatorRegistry** | 自动发现并管理所有技术指标实现（Spring Component 扫描） |
| **SignalGenerator** | 聚合多个指标结果，通过权重投票产生最终信号 |
| **StrategyEngineService** | 协调K线处理、指标计算、信号生成和双写存储 |
| **StrategyEventListener** | 监听K线事件（@Async 异步），触发策略引擎 |
| **SignalPushService** | 通过 WebSocket/STOMP 实时推送信号到前端 |
| **BacktestService** | 基于历史K线模拟交易，计算回测绩效指标 |
| **KLineStore** | K线数据存储与查询（RocksDB 高性能存储） |

### 1.2 数据流

**实时信号生成流程：**

```
交易所 K线数据
      ↓
  KLineEvent (Spring ApplicationEvent)
      ↓
  StrategyEventListener (@Async 异步处理)
      ↓ 按 exchange:symbol:interval 分组
  StrategyEngineService.processKLineUpdate()
      ↓ 查询匹配的已启用策略
  ┌───────────────────────────┐
  │  对每个匹配策略:           │
  │  1. 解析指标配置 (JSON)    │
  │  2. 获取历史K线数据        │
  │  3. SignalGenerator 计算    │
  │  4. 双写 MySQL + RocksDB   │
  │  5. WebSocket 推送信号      │
  └───────────────────────────┘
      ↓
  前端接收信号通知
```

### 1.3 技术栈

| 层级 | 技术 |
|------|------|
| 后端框架 | Java 21, Spring Boot 3.2 |
| ORM | MyBatis Plus 3.5.7 |
| 数据库 | MySQL (持久化), RocksDB (高性能查询) |
| 消息推送 | Spring WebSocket + STOMP + SockJS |
| 构建工具 | Gradle (Version Catalog) |
| 前端框架 | React 19, TypeScript |
| UI 组件库 | Ant Design 6 |
| 构建工具(前端) | Vite 7 |
| 国际化 | react-i18next |

---

## 2. 快速开始

### 2.1 环境准备

确保以下服务已启动：

```bash
# MySQL (默认端口 33306)
mysql -h localhost -P 33306 -u root -p

# Redis (默认端口 6379)
redis-cli ping
```

### 2.2 启动服务

```bash
# 后端 - 在项目根目录执行
./gradlew :web:admin-web:bootRun

# 前端 - 进入前端目录
cd vertex-ui
npm install
npm run dev
```

服务启动后：
- 后端 API：`http://localhost:8080`
- 前端页面：`http://localhost:5173`（Vite 默认端口）
- WebSocket 端点：`ws://localhost:8080/ws/signal`

### 2.3 创建第一个策略

**步骤 1：** 在前端导航至 **策略管理** > **策略配置**

**步骤 2：** 点击 **新建策略** 按钮，填写基本信息：

| 字段 | 示例值 | 说明 |
|------|--------|------|
| 策略名称 | BTC 双均线策略 | 唯一名称 |
| 交易所 | binance | 数据来源交易所 |
| 交易对 | BTCUSDT | 交易品种 |
| K线周期 | 1h | 分析时间周期 |

**步骤 3：** 添加技术指标：

- 点击 **添加指标**，选择 `MA`（简单移动平均线），设置 `period=10`，权重 `60`
- 再次点击 **添加指标**，选择 `RSI`（相对强弱指数），保持默认参数 `period=14`，权重 `40`

**步骤 4：** 提交保存，然后点击 **启用** 按钮

策略启用后，每当收到匹配的K线数据时，系统将自动执行信号分析。

---

## 3. API 参考

> 所有接口统一返回格式：`{ "code": 200, "msg": "success", "data": ... }`

### 3.1 策略管理

**基础路径：** `/admin/strategy`

#### 创建策略

```http
POST /admin/strategy
Content-Type: application/json

{
  "name": "MACD 趋势跟踪",
  "description": "基于 MACD 金叉死叉的趋势策略",
  "exchange": "binance",
  "symbol": "ETHUSDT",
  "interval": "H4",
  "indicatorConfigs": [
    {
      "indicatorType": "MACD",
      "params": { "fast": 12, "slow": 26, "signal": 9 },
      "weight": 70
    },
    {
      "indicatorType": "RSI",
      "params": { "period": 14 },
      "weight": 30
    }
  ]
}
```

**响应：** `{ "code": 200, "data": 1 }` — 返回策略 ID

#### 更新策略

```http
PUT /admin/strategy
Content-Type: application/json

{
  "id": 1,
  "name": "MACD 趋势跟踪 v2",
  "indicatorConfigs": [...]
}
```

#### 删除策略

```http
DELETE /admin/strategy/{id}
```

#### 查询策略详情

```http
GET /admin/strategy/{id}
```

#### 分页查询策略列表

```http
GET /admin/strategy/page?pageNum=1&pageSize=10
```

**响应结构：**
```json
{
  "code": 200,
  "data": {
    "records": [
      {
        "id": 1,
        "name": "MACD 趋势跟踪",
        "exchange": "binance",
        "symbol": "ETHUSDT",
        "interval": "H4",
        "enabled": 1,
        "indicatorConfigs": [...]
      }
    ],
    "total": 1,
    "pageNum": 1,
    "pageSize": 10
  }
}
```

#### 启用 / 禁用策略

```http
POST /admin/strategy/{id}/enable
POST /admin/strategy/{id}/disable
```

### 3.2 信号管理

**基础路径：** `/admin/signal`

#### 分页查询信号

```http
GET /admin/signal/page?pageNum=1&pageSize=10&signalType=BUY&exchange=binance
```

**支持的查询参数：**

| 参数 | 类型 | 说明 |
|------|------|------|
| `pageNum` | int | 页码，默认 1 |
| `pageSize` | int | 每页条数，默认 10 |
| `strategyId` | Long | 按策略 ID 筛选 |
| `exchange` | String | 按交易所筛选 |
| `symbol` | String | 按交易对筛选 |
| `interval` | String | 按K线周期筛选（如 `H1`） |
| `signalType` | String | `BUY` / `SELL` / `NEUTRAL` |
| `startTime` | Long | 起始时间戳（毫秒） |
| `endTime` | Long | 结束时间戳（毫秒） |

#### 查询信号详情

```http
GET /admin/signal/{id}
```

**响应示例：**
```json
{
  "code": 200,
  "data": {
    "id": 100,
    "strategyId": 1,
    "strategyName": "MACD 趋势跟踪",
    "exchange": "binance",
    "symbol": "ETHUSDT",
    "interval": "H4",
    "signalType": "BUY",
    "signalStrength": 75,
    "price": 3245.67,
    "signalTime": 1700000000000,
    "indicators": {
      "macd": -12.34,
      "signal": -15.67,
      "histogram": 3.33,
      "rsi14": 28.5
    },
    "description": "MACD: BUY(金叉); RSI: BUY(超卖)"
  }
}
```

#### 手动触发分析

```http
POST /admin/signal/analyze?strategyId=1
```

该接口会立即对指定策略执行一次信号分析，通常用于调试或手动触发。

### 3.3 策略回测

**基础路径：** `/admin/backtest`

#### 执行回测

```http
POST /admin/backtest/run
Content-Type: application/json

{
  "strategyId": 1,
  "startTime": 1690000000000,
  "endTime": 1700000000000,
  "initialCapital": 10000,
  "positionRatio": 1.0,
  "feeRate": 0.001
}
```

**响应结构：**
```json
{
  "code": 200,
  "data": {
    "strategyId": 1,
    "strategyName": "MACD 趋势跟踪",
    "startTime": 1690000000000,
    "endTime": 1700000000000,
    "initialCapital": 10000.00,
    "finalCapital": 12345.67,
    "totalProfit": 2345.67,
    "returnRate": 23.46,
    "totalTrades": 15,
    "winningTrades": 9,
    "losingTrades": 6,
    "winRate": 60.00,
    "profitLossRatio": 1.85,
    "maxDrawdown": 8.32,
    "maxDrawdownDuration": 12,
    "sharpeRatio": 1.42,
    "trades": [...],
    "equityCurve": [...]
  }
}
```

---

## 4. 技术指标参考

系统内置 7 种技术指标，均实现 `TechnicalIndicator` 接口，通过 Spring Component 扫描自动注册。

### 4.1 MA - 简单移动平均线

| 属性 | 值 |
|------|------|
| 类型代码 | `MA` |
| 默认参数 | `period = 20` |
| 所需数据量 | `period` 根K线 |

**算法：**

```
MA(N) = (C₁ + C₂ + ... + Cₙ) / N
```

其中 C 为收盘价。

**信号逻辑：**
- **BUY**: 收盘价 > MA × 1.001（上穿均线 0.1%）
- **SELL**: 收盘价 < MA × 0.999（下穿均线 0.1%）
- **NEUTRAL**: 其他情况

**输出指标值：** `{"ma20": 42150.5678}`

**适用场景：** 中长期趋势判断，参数越大越平滑、信号越少。

---

### 4.2 EMA - 指数移动平均线

| 属性 | 值 |
|------|------|
| 类型代码 | `EMA` |
| 默认参数 | `period = 20` |
| 所需数据量 | `period × 2` 根K线 |

**算法：**

```
α = 2 / (period + 1)
EMA₀ = SMA(前 period 根K线)
EMAₙ = Cₙ × α + EMAₙ₋₁ × (1 - α)
```

**信号逻辑：** 与 MA 相同（0.1% 阈值判断）

**输出指标值：** `{"ema20": 42180.3456}`

**适用场景：** 对近期价格更敏感，适合跟踪短中期趋势。

---

### 4.3 RSI - 相对强弱指数

| 属性 | 值 |
|------|------|
| 类型代码 | `RSI` |
| 默认参数 | `period = 14` |
| 所需数据量 | `period + 1` 根K线 |

**算法：**

```
Change = Cₙ - Cₙ₋₁

初始: avgGain = sum(gains) / period
      avgLoss = sum(losses) / period

Wilder 平滑:
  avgGain = (prevAvgGain × (period-1) + gain) / period
  avgLoss = (prevAvgLoss × (period-1) + loss) / period

RS = avgGain / avgLoss
RSI = 100 - 100 / (1 + RS)
```

**信号逻辑：**
- **BUY**: RSI < 30（超卖区间）
- **SELL**: RSI > 70（超买区间）
- **NEUTRAL**: 30 ≤ RSI ≤ 70

**输出指标值：** `{"rsi14": 28.4567}`

**适用场景：** 震荡市判断超买超卖，常与趋势指标配合使用。

---

### 4.4 MACD - 移动平均收敛散度

| 属性 | 值 |
|------|------|
| 类型代码 | `MACD` |
| 默认参数 | `fast = 12, slow = 26, signal = 9` |
| 所需数据量 | `slow + signal + 10` 根K线 |

**算法：**

```
MACD Line = EMA(fast) - EMA(slow)
Signal Line = EMA(MACD Line, signal)
Histogram = MACD Line - Signal Line
```

**信号逻辑：**
- **BUY**: 柱状图由负转正（金叉 — MACD 线上穿信号线）
- **SELL**: 柱状图由正转负（死叉 — MACD 线下穿信号线）
- **NEUTRAL**: 其他情况

**输出指标值：** `{"macd": -12.34, "signal": -15.67, "histogram": 3.33}`

**适用场景：** 经典趋势跟踪指标，适合捕捉中期趋势转折点。

---

### 4.5 BOLL - 布林带

| 属性 | 值 |
|------|------|
| 类型代码 | `BOLL` |
| 默认参数 | `period = 20, multiplier = 2.0` |
| 所需数据量 | `period` 根K线 |

**算法：**

```
中轨 (Middle) = SMA(period)
标准差 (σ) = √(Σ(Cᵢ - SMA)² / period)
上轨 (Upper) = SMA + multiplier × σ
下轨 (Lower) = SMA - multiplier × σ
```

**信号逻辑：**
- **BUY**: 收盘价 < 下轨（价格跌出布林带下沿）
- **SELL**: 收盘价 > 上轨（价格突破布林带上沿）
- **NEUTRAL**: 下轨 ≤ 收盘价 ≤ 上轨

**输出指标值：** `{"upper": 43200.00, "middle": 42000.00, "lower": 40800.00, "stdDev": 600.00}`

**适用场景：** 衡量价格波动范围，适合判断价格是否偏离均值。默认参数下约 95% 的价格落在布林带内。

---

### 4.6 KDJ - 随机指标

| 属性 | 值 |
|------|------|
| 类型代码 | `KDJ` |
| 默认参数 | `rsvPeriod = 9, kPeriod = 3, dPeriod = 3` |
| 所需数据量 | `rsvPeriod + 10` 根K线 |

**算法：**

```
RSV = (Close - Low_N) / (High_N - Low_N) × 100
  其中 Low_N, High_N 为最近 rsvPeriod 根K线的最低/最高价

K = 2/3 × prevK + 1/3 × RSV    (初始 K = 50)
D = 2/3 × prevD + 1/3 × K      (初始 D = 50)
J = 3K - 2D
```

**信号逻辑：**
- **BUY**: 金叉（前一根K线 K ≤ D，当前 K > D）
- **SELL**: 死叉（前一根K线 K ≥ D，当前 K < D）
- **NEUTRAL**: 其他情况

**输出指标值：** `{"k": 35.67, "d": 42.33, "j": 22.35}`

**适用场景：** 短期超买超卖判断，金叉/死叉信号频率较高，建议配合趋势指标过滤。J 值 > 100 为极度超买，< 0 为极度超卖。

---

### 4.7 ATR - 平均真实波幅

| 属性 | 值 |
|------|------|
| 类型代码 | `ATR` |
| 默认参数 | `period = 14` |
| 所需数据量 | `period + 1` 根K线 |

**算法：**

```
TR = max(High - Low, |High - prevClose|, |Low - prevClose|)

初始 ATR = average(TR₁ ... TRₙ)
Wilder 平滑: ATR = (prevATR × (period-1) + TR) / period

ATR% = (ATR / Close) × 100
```

**信号逻辑：**
- **始终 NEUTRAL** — ATR 是波动率指标，不产生方向性信号

**输出指标值：** `{"atr": 850.1234, "atrPercent": 2.0145}`

**适用场景：** 衡量市场波动性，常用于设定止损位（如 2×ATR）。ATR% 可跨品种对比波动率。

> **注意：** ATR 不直接产生买卖信号，在策略中用于辅助判断。权重投票中始终投 NEUTRAL 票。

---

## 5. 开发者指南

### 5.1 项目结构

策略模块涉及以下 Gradle 子模块：

```
vertex/
├── model/                              # 数据模型层
│   └── src/main/java/com/vertex/model/
│       ├── entity/strategy/
│       │   ├── Strategy.java           # 策略实体
│       │   ├── Signal.java             # 信号实体
│       │   ├── IndicatorType.java      # 指标类型枚举
│       │   └── SignalType.java         # 信号类型枚举 (BUY/SELL/NEUTRAL)
│       ├── dto/strategy/
│       │   ├── StrategyCreateDTO.java  # 创建策略请求
│       │   ├── StrategyUpdateDTO.java  # 更新策略请求
│       │   ├── StrategyQueryDTO.java   # 策略查询参数
│       │   ├── StrategyIndicatorConfig.java  # 指标配置项
│       │   ├── SignalQueryDTO.java     # 信号查询参数
│       │   └── BacktestConfigDTO.java  # 回测配置
│       └── vo/strategy/
│           ├── StrategyVO.java         # 策略视图
│           ├── SignalVO.java           # 信号视图
│           ├── IndicatorResult.java    # 指标计算结果
│           └── BacktestResultVO.java   # 回测结果
│
├── service/strategy-service/           # 策略服务层
│   └── src/main/java/com/vertex/service/strategy/
│       ├── indicator/
│       │   ├── TechnicalIndicator.java      # 指标接口
│       │   ├── IndicatorRegistry.java       # 指标注册表
│       │   └── impl/
│       │       ├── SimpleMovingAverage.java  # MA
│       │       ├── ExponentialMovingAverage.java  # EMA
│       │       ├── RsiIndicator.java         # RSI
│       │       ├── MacdIndicator.java        # MACD
│       │       ├── BollingerBandsIndicator.java  # BOLL
│       │       ├── KdjIndicator.java         # KDJ
│       │       └── AtrIndicator.java         # ATR
│       ├── engine/
│       │   ├── SignalGenerator.java     # 信号生成器
│       │   ├── StrategyEngineService.java  # 策略引擎
│       │   └── StrategyEventListener.java  # 事件监听
│       ├── backtest/
│       │   └── BacktestService.java     # 回测服务
│       ├── websocket/
│       │   └── SignalPushService.java   # WebSocket 推送
│       ├── controller/
│       │   ├── StrategyController.java  # 策略 API
│       │   ├── SignalController.java    # 信号 API
│       │   └── BacktestController.java  # 回测 API
│       ├── service/
│       │   ├── StrategyServiceImpl.java
│       │   └── SignalServiceImpl.java
│       └── mapper/
│           ├── StrategyMapper.java
│           └── SignalMapper.java
│
└── web/admin-web/                      # Web 入口
    └── src/main/java/com/vertex/admin/web/config/
        └── WebSocketConfig.java        # WebSocket 配置
```

### 5.2 添加自定义指标

添加自定义技术指标只需 **3 步**：

#### 步骤 1：在枚举中注册新类型

**文件：** `model/.../entity/strategy/IndicatorType.java`

```java
public enum IndicatorType {
    MA("MA", "简单移动平均线"),
    EMA("EMA", "指数移动平均线"),
    RSI("RSI", "相对强弱指数"),
    MACD("MACD", "MACD指标"),
    BOLL("BOLL", "布林带"),
    KDJ("KDJ", "随机指标"),
    ATR("ATR", "平均真实波幅"),
    // ↓ 在此添加新指标
    MY_INDICATOR("MY_INDICATOR", "我的自定义指标");

    // ... 构造方法和字段
}
```

#### 步骤 2：实现 TechnicalIndicator 接口

**新建文件：** `service/strategy-service/.../indicator/impl/MyIndicator.java`

```java
package com.vertex.service.strategy.indicator.impl;

import com.vertex.model.entity.quote.KLine;
import com.vertex.model.entity.strategy.IndicatorType;
import com.vertex.model.entity.strategy.SignalType;
import com.vertex.model.vo.strategy.IndicatorResult;
import com.vertex.service.strategy.indicator.TechnicalIndicator;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class MyIndicator implements TechnicalIndicator {

    @Override
    public IndicatorType type() {
        return IndicatorType.MY_INDICATOR;
    }

    @Override
    public int requiredDataPoints(Map<String, Object> params) {
        // 返回该指标需要的最少K线数量
        int period = getParam(params, "period", 20);
        return period;
    }

    @Override
    public IndicatorResult calculate(List<KLine> klines, Map<String, Object> params) {
        int period = getParam(params, "period", 20);

        // ... 你的计算逻辑 ...
        BigDecimal value = BigDecimal.ZERO; // 替换为实际计算

        // 构建输出指标值
        Map<String, Object> values = new HashMap<>();
        values.put("myValue", round(value));

        // 判断信号方向
        SignalType signal = SignalType.NEUTRAL;
        String suggestion = "NEUTRAL";

        // 例: 值 > 某阈值 → BUY
        if (value.compareTo(new BigDecimal("80")) > 0) {
            signal = SignalType.BUY;
            suggestion = "BUY(自定义条件满足)";
        }

        return IndicatorResult.builder()
                .indicatorType(type())
                .values(values)
                .suggestion(signal == SignalType.BUY
                        ? IndicatorResult.SignalSuggestion.BUY
                        : signal == SignalType.SELL
                        ? IndicatorResult.SignalSuggestion.SELL
                        : IndicatorResult.SignalSuggestion.NEUTRAL)
                .description("MY_INDICATOR: " + suggestion)
                .build();
    }

    // ---- 辅助方法 ----

    private int getParam(Map<String, Object> params, String key, int defaultVal) {
        if (params == null || !params.containsKey(key)) return defaultVal;
        Object v = params.get(key);
        return v instanceof Number ? ((Number) v).intValue() : defaultVal;
    }

    private BigDecimal round(BigDecimal val) {
        return val.setScale(4, RoundingMode.HALF_UP);
    }
}
```

> **关键点：** 标注 `@Component` 后，Spring 启动时会自动扫描并注册到 `IndicatorRegistry`，无需手动配置。

#### 步骤 3：前端支持（可选）

如需在前端策略配置表单中支持该指标：

1. **`vertex-ui/src/api/strategy.ts`** — 更新 `IndicatorType` 类型和标签映射
2. **`vertex-ui/src/pages/strategy/StrategyConfig.tsx`** — 在 `IndicatorParamsFields` 组件中添加参数表单
3. **`vertex-ui/src/i18n/locales/`** — 添加国际化文案

### 5.3 信号权重聚合机制

`SignalGenerator` 使用 **加权投票制** 聚合多个指标的结果：

```
策略配置示例:
  - MACD (权重 60)  → 计算结果: BUY
  - RSI  (权重 30)  → 计算结果: BUY
  - ATR  (权重 10)  → 计算结果: NEUTRAL (始终中性)

投票统计:
  buyWeight     = 60 + 30 = 90
  sellWeight    = 0
  neutralWeight = 10
  totalWeight   = 100

最终信号: BUY（buyWeight 最大）
信号强度: 90 / 100 × 100 = 90%
```

**聚合规则：**

1. 遍历所有配置的指标，调用 `calculate()` 获得各指标建议
2. 按建议方向累加权重：
   - 指标建议 BUY → `buyWeight += indicator.weight`
   - 指标建议 SELL → `sellWeight += indicator.weight`
   - 指标建议 NEUTRAL / 计算异常 → `neutralWeight += indicator.weight`
3. 最终信号取权重最大的方向：
   - `buyWeight > sellWeight && buyWeight > neutralWeight` → **BUY**
   - `sellWeight > buyWeight && sellWeight > neutralWeight` → **SELL**
   - 其他 → **NEUTRAL**
4. 信号强度 = 主导方向权重 / 总权重 × 100（百分比）

> **设计建议：** 至少使用 2 个互补指标（如趋势 + 震荡），权重总和建议为 100 便于直观理解。

---

## 6. 策略配置教程

### 6.1 创建策略

在 **策略管理** 页面，点击 **新建策略** 打开配置弹窗：

**基本信息填写：**

| 字段 | 必填 | 说明 |
|------|------|------|
| 策略名称 | 是 | 不可重复 |
| 策略描述 | 否 | 策略目标及逻辑说明 |
| 交易所 | 是 | `binance` 或 `okx` |
| 交易对 | 是 | 如 `BTCUSDT`, `ETHUSDT` |
| K线周期 | 是 | 见下方支持的周期列表 |

**支持的K线周期：**

| 代码 | 含义 | 代码 | 含义 |
|------|------|------|------|
| M1 | 1 分钟 | H4 | 4 小时 |
| M3 | 3 分钟 | H6 | 6 小时 |
| M5 | 5 分钟 | H8 | 8 小时 |
| M15 | 15 分钟 | H12 | 12 小时 |
| M30 | 30 分钟 | D1 | 1 天 |
| H1 | 1 小时 | D3 | 3 天 |
| H2 | 2 小时 | W1 | 1 周 |
| — | — | MN1 | 1 月 |

### 6.2 指标参数配置

创建策略时，至少需添加 **1 个指标**。点击 **添加指标** 后配置：

#### MA 参数

| 参数 | 默认值 | 范围建议 | 说明 |
|------|--------|----------|------|
| `period` | 20 | 5 - 200 | 计算周期，越大越平滑 |

#### EMA 参数

| 参数 | 默认值 | 范围建议 | 说明 |
|------|--------|----------|------|
| `period` | 20 | 5 - 200 | 计算周期 |

#### RSI 参数

| 参数 | 默认值 | 范围建议 | 说明 |
|------|--------|----------|------|
| `period` | 14 | 6 - 25 | 计算周期 |

#### MACD 参数

| 参数 | 默认值 | 范围建议 | 说明 |
|------|--------|----------|------|
| `fast` | 12 | 5 - 20 | 快线 EMA 周期 |
| `slow` | 26 | 20 - 40 | 慢线 EMA 周期 |
| `signal` | 9 | 5 - 15 | 信号线 EMA 周期 |

#### BOLL 参数

| 参数 | 默认值 | 范围建议 | 说明 |
|------|--------|----------|------|
| `period` | 20 | 10 - 50 | 中轨 SMA 周期 |
| `multiplier` | 2.0 | 1.0 - 3.0 | 标准差倍数 |

#### KDJ 参数

| 参数 | 默认值 | 范围建议 | 说明 |
|------|--------|----------|------|
| `rsvPeriod` | 9 | 5 - 21 | RSV 计算周期 |
| `kPeriod` | 3 | 2 - 5 | K 线平滑因子（保留字段） |
| `dPeriod` | 3 | 2 - 5 | D 线平滑因子（保留字段） |

> **注：** KDJ 的 K/D 线使用固定的 2/3 平滑系数，`kPeriod` 和 `dPeriod` 参数为预留扩展，当前版本未动态使用。

#### ATR 参数

| 参数 | 默认值 | 范围建议 | 说明 |
|------|--------|----------|------|
| `period` | 14 | 7 - 21 | 计算周期 |

### 6.3 权重系统

每个指标需设定 **权重值**（1-100），决定该指标在最终信号判定中的话语权。

**推荐配置方案：**

| 方案 | 指标组合 | 权重分配 | 适用场景 |
|------|----------|----------|----------|
| 趋势跟踪 | MACD + MA | 60 : 40 | 单边行情 |
| 震荡反转 | RSI + BOLL | 50 : 50 | 横盘震荡 |
| 综合策略 | MACD + RSI + BOLL | 40 : 30 : 30 | 通用 |
| 短线策略 | KDJ + EMA | 55 : 45 | 短周期频繁交易 |
| 趋势+波动 | MACD + RSI + ATR | 50 : 30 : 20 | 趋势确认+波动过滤 |

> **提示：** ATR 始终输出 NEUTRAL，分配权重给 ATR 相当于增加"弃权票"，可降低误信号率。

### 6.4 启用与禁用

- **启用**：策略将在匹配的K线数据到达时自动执行分析
- **禁用**：策略停止接收K线事件，已生成的历史信号不受影响

在策略列表的操作列点击 **启用** / **禁用** 按钮切换。

---

## 7. 回测教程

### 7.1 执行回测

1. 在 **策略管理** 页面，找到要回测的策略
2. 点击操作列的 **回测** 按钮（试管图标）
3. 在弹出的回测面板中设置参数
4. 点击 **执行回测**，等待结果返回

### 7.2 回测参数说明

| 参数 | 默认值 | 说明 |
|------|--------|------|
| 时间范围 | 无默认 | 回测的起止时间，需确保该时间段内有足够的K线数据 |
| 初始资金 | 10,000 | 模拟账户初始金额 |
| 仓位比例 | 1.0 (100%) | 每次开仓使用的资金比例。0.5 = 半仓 |
| 手续费率 | 0.001 (0.1%) | 每笔交易的手续费率（单边） |

**数据要求：**

系统会自动在起始时间之前获取额外的K线数据（用于指标预热计算）。如果历史数据不足，将返回 `回测数据不足` 错误。

### 7.3 结果指标解读

| 指标 | 说明 | 参考范围 |
|------|------|----------|
| **收益率** | (最终资金 - 初始资金) / 初始资金 × 100% | > 0 为盈利 |
| **胜率** | 盈利交易数 / 总交易数 × 100% | > 50% 较好 |
| **盈亏比** | 平均盈利 / 平均亏损 | > 1.5 较好 |
| **最大回撤** | 资金从峰值到谷值的最大下降百分比 | < 20% 较理想 |
| **最大回撤持续** | 回撤持续的K线根数 | 越短越好 |
| **夏普比率** | (平均收益 / 收益标准差)，衡量风险调整后收益 | > 1.0 较好，> 2.0 优秀 |
| **总交易次数** | 完成的完整买卖交易数 | 需足够多才有统计意义 |

**常见问题：**
- **交易次数为 0**：策略在回测期间未产生任何信号，检查指标参数或时间范围
- **收益率极端**：可能仓位比例过高或时间范围选择不当
- **胜率高但亏损**：盈亏比过低，单次亏损金额远大于盈利

### 7.4 资金曲线与交易记录

**资金曲线：**
- 回测面板底部展示资金变化折线图
- 每 10 根K线采样一个数据点，避免数据过多
- 曲线上升趋势表示策略持续盈利

**交易记录表格：**

| 列 | 说明 |
|------|------|
| 入场时间 | 买入信号触发的K线时间 |
| 出场时间 | 卖出信号触发的K线时间 |
| 入场价格 | 开仓时的收盘价 |
| 出场价格 | 平仓时的收盘价 |
| 数量 | 开仓的资产数量 |
| 盈亏 | 该笔交易的绝对盈亏金额 |
| 盈亏比例 | 该笔交易的收益率百分比 |

> 盈利交易显示为绿色，亏损交易显示为红色。

---

## 8. 信号监控教程

### 8.1 实时推送原理

信号监控页面通过 WebSocket 接收实时信号：

```
后端 SignalPushService
      ↓ STOMP 协议
  /topic/signal (全局广播)
  /topic/signal/{exchange}/{symbol} (按交易对)
      ↓
前端 useSignalWebSocket Hook
      ↓
  notification 弹窗 (BUY/SELL 信号)
  信号列表实时更新 (第一页时)
```

**连接状态指示器：**
- 页面标题旁显示连接状态
- 🟢 **Live**：WebSocket 已连接，可接收实时信号
- 🔴 **Offline**：连接断开，将在 5 秒后自动重连

### 8.2 信号列表与筛选

**信号列表展示：**

| 列 | 说明 |
|------|------|
| 信号时间 | K线的开盘时间 |
| 策略名称 | 产生该信号的策略 |
| 交易所 | 数据来源交易所 |
| 交易对 | 交易品种 |
| K线周期 | 分析使用的周期 |
| 信号类型 | BUY（绿色）/ SELL（红色）/ NEUTRAL（灰色）|
| 信号强度 | 0-100% 进度条，反映指标共识程度 |
| 价格 | 信号产生时的收盘价 |
| 操作 | 查看详情 |

**筛选条件：**

| 筛选器 | 说明 |
|--------|------|
| 交易所 | 下拉选择 Binance / OKX |
| 交易对 | 输入框，如 BTCUSDT |
| K线周期 | 下拉选择 |
| 信号类型 | BUY / SELL / NEUTRAL |
| 策略名称 | 下拉选择已有策略 |
| 时间范围 | 日期时间范围选择器 |

设置筛选条件后，点击 **搜索** 按钮查询。

### 8.3 手动分析触发

在筛选栏下方，显示所有 **已启用** 策略的快捷触发按钮：

```
手动分析: [⚡ BTC 双均线策略] [⚡ ETH MACD 策略] ...
```

点击按钮会立即触发对应策略的一次信号分析，分析结果将在约 1 秒后刷新到列表中。

**使用场景：**
- 不想等待下一根K线收线
- 调试策略时快速查看信号
- 手动确认当前市场信号

### 8.4 信号详情查看

点击信号列表中的 **详情** 按钮，弹出详情窗口显示：

| 字段 | 说明 |
|------|------|
| 策略名称 | 产生该信号的策略 |
| 信号类型 | BUY / SELL / NEUTRAL（带颜色标签） |
| 交易所 | 大写显示 |
| 交易对 | 交易品种 |
| K线周期 | 显示为中文标签 |
| 信号强度 | 百分比进度条 |
| 价格 | 信号时刻的收盘价 |
| 信号时间 | 格式化的日期时间 |
| 指标值 | 各指标的具体计算值（如 `macd: -12.34`）|
| 描述 | 各指标的信号建议汇总 |

---

## 9. 配置参考

### 9.1 后端配置项

**文件：** `web/admin-web/src/main/resources/application.yaml`

```yaml
server:
  port: 8080                            # 服务端口

spring:
  datasource:
    url: jdbc:mysql://localhost:33306/vertex    # MySQL 连接
    username: root
    password: root

  data:
    redis:
      host: localhost                   # Redis 地址
      port: 6379

vertex:
  strategy:
    enabled: true                       # 策略模块总开关
    rocksdb:
      data-dir: ./data/rocksdb/strategy # RocksDB 数据目录
    engine:
      max-kline-history: 500            # 每次分析获取的最大K线数量
      only-closed-klines: true          # 是否仅处理已收线K线
```

**配置项说明：**

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `vertex.strategy.enabled` | `true` | 设为 `false` 可完全禁用策略引擎 |
| `vertex.strategy.rocksdb.data-dir` | `./data/rocksdb/strategy` | RocksDB 信号存储路径 |
| `vertex.strategy.engine.max-kline-history` | `500` | 单次获取K线上限，增大可支持更大周期指标 |
| `vertex.strategy.engine.only-closed-klines` | `true` | `true` = 仅在K线收线时分析，避免盘中噪音 |

### 9.2 WebSocket 配置

WebSocket 使用 STOMP 协议，通过 SockJS 提供浏览器兼容性：

| 配置项 | 值 | 说明 |
|--------|------|------|
| 端点 | `/ws/signal` | SockJS 连接地址 |
| 消息代理前缀 | `/topic` | 订阅主题前缀 |
| 应用前缀 | `/app` | 客户端发送消息前缀 |
| CORS | `*` | 允许所有来源 |

**可订阅的主题：**

| 主题 | 说明 |
|------|------|
| `/topic/signal` | 所有信号（全局广播） |
| `/topic/signal/{exchange}/{symbol}` | 按交易对过滤，如 `/topic/signal/binance/BTCUSDT` |

**前端连接方式：**

前端通过 `useSignalWebSocket` Hook 自动管理连接，地址为 `/api/ws/signal/websocket`（经 Vite 代理转发）。连接断开后 5 秒自动重连。

### 9.3 数据库表结构

#### stg_strategy - 策略表

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | BIGINT | 主键 |
| `name` | VARCHAR | 策略名称（唯一） |
| `description` | TEXT | 策略描述 |
| `exchange` | VARCHAR | 交易所标识 |
| `symbol` | VARCHAR | 交易对 |
| `interval` | VARCHAR | K线周期代码 |
| `indicator_configs` | LONGTEXT | 指标配置 JSON 数组 |
| `enabled` | INT | 0=禁用, 1=启用 |
| `created_time` | DATETIME | 创建时间 |
| `updated_time` | DATETIME | 更新时间 |
| `deleted` | INT | 逻辑删除标记 |

`indicator_configs` JSON 示例：

```json
[
  {
    "indicatorType": "MACD",
    "params": { "fast": 12, "slow": 26, "signal": 9 },
    "weight": 60
  },
  {
    "indicatorType": "RSI",
    "params": { "period": 14 },
    "weight": 40
  }
]
```

#### stg_signal - 信号表

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | BIGINT | 主键 |
| `strategy_id` | BIGINT | 关联策略 ID |
| `strategy_name` | VARCHAR | 策略名称（冗余） |
| `exchange` | VARCHAR | 交易所 |
| `symbol` | VARCHAR | 交易对 |
| `interval` | VARCHAR | K线周期 |
| `signal_type` | VARCHAR | BUY / SELL / NEUTRAL |
| `signal_strength` | INT | 信号强度 0-100 |
| `price` | DECIMAL | 信号时刻价格 |
| `signal_time` | BIGINT | 信号时间（毫秒时间戳） |
| `indicators` | LONGTEXT | 指标值 JSON |
| `description` | TEXT | 信号描述 |
| `created_time` | DATETIME | 创建时间 |
| `deleted` | INT | 逻辑删除标记 |

---

## 附录

### A. 常见问题

**Q: 策略启用后没有产生信号？**

A: 检查以下几点：
1. 是否有对应交易对和周期的K线数据在入库
2. 历史K线数量是否满足指标的最低要求（如 MACD 需要至少 45 根）
3. `only-closed-klines` 设为 `true` 时，需等待K线收线

**Q: 回测返回"数据不足"错误？**

A: 回测需要在起始时间之前有足够的K线用于指标预热。建议：
- 确保 RocksDB/KLineStore 中有充足的历史数据
- 缩短回测时间范围或选择数据更完整的品种

**Q: WebSocket 显示 Offline？**

A: 可能原因：
1. 后端服务未启动或不可达
2. `admin-web` 的 `build.gradle` 未引入 `spring-boot-starter-websocket`
3. 前端代理配置（Vite）中 `/api` 路径未正确转发
4. 浏览器控制台查看具体连接错误

**Q: 如何只接收某个交易对的信号？**

A: WebSocket 支持按交易对订阅。修改前端 `useSignalWebSocket` 的订阅主题为：
```
/topic/signal/binance/BTCUSDT
```

### B. 性能建议

| 场景 | 建议 |
|------|------|
| 大量策略运行 | 控制启用策略数量，避免超过 50 个同时运行 |
| 高频K线周期（M1） | 增大 `max-kline-history`，确保指标计算准确 |
| 回测大时间范围 | 可能耗时较长，建议分段回测后对比 |
| 信号存储增长 | 定期清理过期的 NEUTRAL 信号，减少数据库压力 |

### C. 版本变更记录

| 版本 | 日期 | 变更 |
|------|------|------|
| 1.0 | - | 初始版本：7 种指标、策略回测、WebSocket 推送 |
