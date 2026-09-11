# mm-service 完整设计与架构文档

> 版本: 2026-09-05
> 状态: PAPER 可跑，LIVE 需补 Layer3/4 自动触发 + 非对称步长

---

## 一、核心思想

**这就是标准双向网格**（bidirectional pending grid），在此之上叠加：

1. **信号驱动的步长调整** —— 波动率变化时缩放 step；哪一侧被"吃"得凶就把该侧步长加大（延缓累积仓位）
2. **Depletion 快反应** —— ADX 是 5min K 线级别，反应慢；Depletion（挂单被吃速率 + 长空不对称）事件驱动，秒级感知波动
3. **5 层风控保护** —— 从"减挡步长"到"分侧停机"到"账户熔断"

核心思想一句话：**"maker-only（GTX post-only）+ 网格套利 + 信号+风控双入口"**。

---

## 二、双入口模型（心智地图）

```
┌─────────────────────────────────────────────────────────────┐
│                     信号 Signals（被动，缺失=默认）              │
│  centerPrice, stepPct, longSideEnabled, shortSideEnabled,     │
│  currentLayer, ADX, depletionPct, trendScore, marketQuote,    │
│  orderBook, gtxRejectRate...                                  │
└────────────────────────────┬────────────────────────────────┘
                             │ read
                             ▼
┌─────────────────────────────────────────────────────────────┐
│                网格核心 Grid Engine（纯逻辑）                  │
│  • 初始化 slot 拓扑                                            │
│  • fill 事件 → 生成 CLOSE + rearm OPEN                         │
│  • 空档定时补挂                                                │
│  • 中枢漂移触发 shiftWindow                                    │
└────────────────────────────┬────────────────────────────────┘
                             │ intents
                             ▼
┌─────────────────────────────────────────────────────────────┐
│                风控 Risk Guards（主动，接管流程）                 │
│  • Layer3 追价减仓 (ProactiveReducer)                          │
│  • Layer4 分侧停 (SideStopService)                             │
│  • Layer5 账户熔断 (AccountRiskAggregator)                     │
│  • Emergency 4 模式 (EmergencyStopService)                     │
│  • LadderClose 梯队平仓 (LadderCloseScheduler)                 │
└────────────────────────────┬────────────────────────────────┘
                             │ gate
                             ▼
┌─────────────────────────────────────────────────────────────┐
│         下单层 OrderClientRouter（safety net + 路由）           │
│  • SmartPricePlacer 深度感知调价                                │
│  • SymbolFilters tick/step 对齐 + minNotional validate         │
│  • Paper 走 PaperOrderClient（内存模拟）                         │
│  • Live 走 MmOrderClient（HMAC 签名 → 币安 REST）                 │
└──────────────────────────────────────────────────────────────┘
```

**信号缺失 = 默认行为**（如 ADX 10min 过期 → 打分跳过该维度）
**风控接管 = 中断/覆盖流程**（如 EMERGENCY_STOPPED → 拒绝所有 start）

---

## 三、策略设计

### 3.1 网格几何

```
                                    upperPrice_5  (SELL @ center × 1.010)
                                    upperPrice_4  (SELL @ center × 1.008)
                                    upperPrice_3  (SELL @ center × 1.006)
                                    upperPrice_2  (SELL @ center × 1.004)
                                    upperPrice_1  (SELL @ center × 1.002)
    ═════════════════════════════════ centerPrice ═════════════════════════
                                    lowerPrice_1  (BUY  @ center / 1.002)
                                    lowerPrice_2  (BUY  @ center / 1.004)
                                    lowerPrice_3  (BUY  @ center / 1.006)
                                    lowerPrice_4  (BUY  @ center / 1.008)
                                    lowerPrice_5  (BUY  @ center / 1.010)
```

- **单元素**：`step_pct`（例 0.2%）× 档位序号 `i`（1..5）
- **对齐**：所有价按 symbol 的 `tickSize` round（BUY 侧 FLOOR，SELL 侧 CEILING）
- **数量**：`qtyPerGrid`（例 0.01 ETH）按 `stepSize` round

### 3.2 成交响应（每次 OPEN 成交派生 2 单）

```
BUY @2445 成交
  ├─ ① CLOSE SELL @2445 × (1 + step)  ← 止盈单
  └─ ② rearm BUY @2445                ← 保持满档
```

**目的**：价格来回震荡 N 次 → arb 收益 N 次 × step × qty，同时网格永远满档。

### 3.3 信号驱动（Adaptive 5min）

```
每 5min AdaptiveScheduler：
  1. 拉 K 线（trendLayer1Interval，默认 5m）
  2. 算 ADX / ATR / EMA20
  3. AdaptiveStepCalculator 决定：
      • 波动率 > baseline × 2 → step × 1.5
      • 波动率 < baseline × 0.5 → step × 0.7
      • EMA20 漂移 > 0.5% → shiftWindow 到新中枢
  4. shiftWindow：撤远离新中枢的档 + 挂新档
  5. refillEmptySlots：兜底补 GTX 拒单 / 未 rearm 的空档
```

### 3.4 Depletion 事件驱动

```
每次 fill 事件：
  WindowDepletionAnalyzer.onOpenFill(side, timeMs)
     ├─ 更新 fills 队列（最近 200 次）
     ├─ 更新连续同侧计数
     └─ 更新最后反向时间

计算 depletion pct（当前实现）：
  depletion = fills_since_last_reshuffle / windowSize
  （不用 "windowSize - active" 因为 GTX 拒单会误报为 fill）
```

**衍生指标进综合评分**：
- depletionPct（LONG/SHORT）
- speedPer3Min / speedPer30Min
- asymmetryRatio（LONG 速率 / SHORT 速率）
- consecutiveSameSide
- ADX（TrendIndicatorCache 10min TTL）

### 3.5 综合评分 → Layer 映射

`TrendScoreEvaluator` 5 维打分（权重可配）：

| 维度 | 权重默认 | 触发规则 |
|---|---|---|
| ADX | 3 | > `trend_layer2_adx_enter`（默认 25） |
| Depletion | 3 | > `depletion_pct_layer2`（默认 60%） |
| Asymmetry | 2 | > 3× 或 < 1/3 |
| Consecutive | 1 | > 4 次同侧 |
| Reverse gap | 1 | > `depletion_time_layer2_sec` 无反向 |

Score → Layer 映射：
- 0-2 → NORMAL
- 3-4 → LAYER1
- 5-6 → LAYER2 (→ 挂梯队平仓触发)
- 7-8 → LAYER3 (→ 主动追价减仓 30%)  **⚠️ 目前未自动触发**
- ≥9 → LAYER4 (→ 分侧停机)  **⚠️ 目前未自动触发**

### 3.6 5 层保护动作

| Layer | 触发条件 | 动作 | 谁做 | 自动化 |
|---|---|---|---|---|
| L1 | 综合分 3-4 | 观察打分 | GridEngine | ✅ |
| L2 | 分 5-6 + LadderClose | 挂梯队 GTX 平仓 | LadderCloseScheduler 30s | ✅ |
| L3 | 分 7-8 | ProactiveReducer 追价平仓 30% | 应由 GridEngine 触发 | ❌ **半成品** |
| L4 | 分 ≥9 or ADX+浮亏 | SideStopService 撤+全平该侧 | 应由 scheduler 触发 | ❌ **半成品** |
| L5 | MARGIN_CALL 事件 (强制) 或 账户浮亏% > `account_emergency_pnl_pct` (opt-in) | 所有 LIVE 策略 emergencyStop | AccountRiskAggregator 1min | ✅ (opt-in 后) |
| Emergency | 用户手动 | 4 模式（SOFT/GRACEFUL/LADDER/MARKET） | 用户点按钮 | ✅ |

### 3.7 P&L 归因

```
每次 CLOSE 成交：
  OrderPairMatcher.onCloseFilled(closeOrder)
    ├─ FIFO 配对最早未 pair 的 OPEN
    ├─ pair_pnl = (closePrice - openPrice) × qty (LONG)
    │           或 (openPrice - closePrice) × qty (SHORT)
    ├─ 累加到 strategy.total_arbitrage_pnl
    └─ 标记 open.pair_order_id = close.id

未配对的 OPEN（长期累积）→ directional_pnl（未实现浮盈亏）
资金费 → funding_pnl（每 15min 拉 /fapi/v1/income）
```

**理想状态**：arbitrage_pnl 稳定为正，directional_pnl 接近 0。

---

## 四、架构

### 4.1 模块划分

```
mm-service (独立 Spring @ConditionalOnProperty)
├── config/
│   ├── MmProperties           (yml 配置)
│   └── MmAutoConfiguration    (Spring Boot 3 auto-config)
├── binance/
│   ├── MmOrderClient          (GTX post-only 下单)
│   ├── BinanceAccountClient   (dual/leverage/marginType/income/positionRisk)
│   ├── BinanceTimeSync        (15min 时间校准)
│   ├── SymbolFilters(Cache)   (tick/step/minQty/minNotional)
│   └── Mm(Order)Request/Result
├── market/
│   ├── BookTickerSubscriber   (bid/ask WS，refCount + watchdog)
│   ├── DepthSubscriber        (depth10@100ms WS)
│   ├── AggTradeSubscriber     (Paper fill 用)
│   ├── MarketQuoteCache       (5s TTL)
│   ├── OrderBookCache
│   └── SmartPricePlacer       (深度感知避薄簿)
├── userstream/
│   ├── ListenKeyManager       (30min keepalive)
│   ├── UserDataStreamManager  (私有 WS，多层重连修复)
│   └── event/*                (Order/Account/MarginCall 事件)
├── risk/
│   ├── WindowDepletionAnalyzer  (fills 队列 + 速率 + 不对称)
│   ├── TrendScoreEvaluator      (5 维打分 → Layer)
│   └── DepletionState           (数据类)
├── grid/
│   ├── entity/                 (GridStrategy, GridOrder, ...)
│   ├── mapper/                 (MyBatis)
│   ├── controller/             (REST: CRUD + start/stop/emergency/recover)
│   ├── dto/vo/                 (前后端契约)
│   ├── service/
│   │   ├── GridStrategyService    (生命周期总控 start/stop/emergency)
│   │   ├── GridRecoveryService    (JVM 重启自动恢复)
│   │   ├── SlidingWindowManager   (网格状态机 + 补 rearm + 补空档)
│   │   ├── GridState/GridSlot     (内存状态)
│   │   ├── GridEngine             (WS 事件消费 + 打分 + 触发)
│   │   ├── OrderIntentExecutor    (batch 5/10 + 落 DB)
│   │   ├── OrderClientRouter      (Paper/Live 路由 + normalize)
│   │   ├── AdaptiveScheduler      (5min: step/center/shift/refill)
│   │   ├── LadderCloseScheduler   (30s: L2B 梯队挂平仓)
│   │   ├── LadderCloseManager     (梯队价格计算)
│   │   ├── ProactiveReducer       (L3: 追价平仓，MARKET 兜底)
│   │   ├── SideStopService        (L4: 分侧撤+平)
│   │   ├── EmergencyStopService   (4 模式主动动作)
│   │   ├── AccountRiskAggregator  (L5: 账户级熔断)
│   │   ├── PnlSnapshotScheduler   (1min: 快照 + 内存→DB sync)
│   │   ├── OrderPairMatcher       (FIFO arb P&L 归因)
│   │   ├── WsReconciliationService (WS 重连全量对账)
│   │   ├── PositionInfoResolver   (从 grid_order 算持仓)
│   │   ├── AccountCredentialResolver (API key 解析 + PAPER stub)
│   │   ├── TrendIndicatorCache    (ADX 10min 缓存)
│   │   └── GridEventLogger        (事件日志封装)
│   └── backtest/GridBacktestService (K 线级回测)
└── paper/
    ├── PaperOrderClient       (模拟下单 + GTX 拒单率)
    ├── PaperOrderBook         (内存簿，DB seed id)
    ├── PaperFillSimulator     (aggTrade 驱动模拟成交)
    ├── PaperAccountState      (模拟持仓)
    └── PaperOrder             (数据类)
```

### 4.2 数据流

#### 4.2.1 启动流程（LIVE）
```
POST /admin/mm/grid/{id}/start
     │
     ▼
GridStrategyService.start(id)
  ├─ subscribe BookTicker + Depth（refCount）
  ├─ resolveCenterWithWait 5s 等 quote
  ├─ isPaper 判定 → 走对应分支
  │   PAPER: aggTradeSubscriber.subscribe + PaperFillSimulator listener
  │   LIVE:  ensureDualSideMode + setLeverage + setMarginType
  │        + userStreamManager.start（listenKey WS）
  │        + ensureListenersRegistered (GridEngine + Reconcile + Risk)
  ├─ slidingWindow.initialize（tick 对齐 → 10 个 intents）
  ├─ persistRunningState (先持久化再挂单，crash-safe)
  ├─ executor.execute(intents) → router → binance/paper
  └─ trackClientOrder 回填 map
```

#### 4.2.2 成交事件流
```
Binance WS (LIVE) or PaperFillSimulator (PAPER)
     │ ORDER_TRADE_UPDATE 事件
     ▼
GridEngine.onOrderUpdate
  ├─ clientOrderId → strategyId 查找（内存 or DB 反查）
  ├─ 更新 grid_order status/filled/avgPrice
  ├─ 事件日志 FILL
  ├─ isOpen? → depletionAnalyzer.onOpenFill
  ├─ !isOpen? → depletionAnalyzer.onCloseFill + pairMatcher.onCloseFilled
  ├─ isOpen? → slidingWindow.onOrderFilled
  │              └─ 生成 [CLOSE 单 + rearm OPEN 单] intents
  ├─ executor.execute(intents) → router → binance
  └─ evaluateTrendScore (100ms debounce)
       └─ CAS state.currentLayer
```

#### 4.2.3 定时任务频率
| Scheduler | 频率 | 职责 |
|---|---|---|
| `AdaptiveScheduler` | 5min | 补空档 + 算 ADX/ATR + 决定 step/center shift |
| `LadderCloseScheduler` | 30s | 判定 L2B 触发梯队 or 恢复撤梯队 |
| `PnlSnapshotScheduler` | 1min | 采样 pnl_snapshot + 内存 → DB batch sync + 拉 fundingPnl |
| `AccountRiskAggregator.periodicEvaluate` | 1min | 账户级 pnl 熔断 |
| `WsConnectionWatchdog` | 30s | UserDataStream 无消息检测 |
| 3 个 subscriber watchdog | 30s | 各 WS 无消息强制重连 |
| `BinanceTimeSync` | 15min | 校准 offset |
| `SymbolFiltersCache` | 24h | 全量刷 exchangeInfo |
| `PnlSnapshotScheduler.fetchFundingIncome` | 15min/策略 | 拉资金费累计 |

### 4.3 内存 vs DB 权威分工

| 数据 | 权威 | 何时 sync 到 DB |
|---|---|---|
| `GridState`（center/step/slots/qty/layer） | 内存 | 每 1min（`PnlSnapshotScheduler.syncStateToDb`） |
| `MarketQuoteCache` / `OrderBookCache` | 内存 | 从不持久化 |
| `PaperOrderBook` | 内存 | 从不持久化（重启就丢） |
| `clientOrderToStrategy` | 内存 + DB fallback | 每次挂单 |
| `grid_order`（挂单/成交流水） | DB | 立即（每次挂单/成交） |
| `grid_event`（事件日志） | DB | 立即 |
| `grid_pnl_snapshot` | DB | 每 1min |

**原则**：高频跳动的用内存 + UI 从 `/status` 端点读内存；DB 只做崩溃恢复兜底。

### 4.4 Paper vs Live 隔离

```
strategy.execution_mode = "PAPER" | "LIVE"
                      │
                      ▼
OrderClientRouter.isPaper(strategy)?
  ├─ true  → PaperOrderClient（内存 book + GTX reject rate 模拟）
  └─ false → MmOrderClient（真实 REST + HMAC 签名）

以下服务对 PAPER 有特殊处理：
  ✓ AccountCredentialResolver.resolveOrPaper — 允许账户不存在返回 stub
  ✓ WsReconciliationService — 跳过 PAPER 策略（不拉真实 openOrders）
  ✓ AccountRiskAggregator.emergencyStopAll — MARGIN_CALL 只停 LIVE
  ✓ GridRecoveryService — PAPER 转 STOPPED，LIVE 自动恢复
```

**保证**：PAPER 策略绝不产生真实交易所调用（除市场数据订阅，那是公有流）。

### 4.5 崩溃恢复

```
JVM 启动完成 (ApplicationReadyEvent)
     │
     ▼
GridRecoveryService.recoverAll
  ├─ PAPER 策略:
  │    ├─ markLiveOrdersCanceled（清 grid_order 僵尸）
  │    ├─ status → STOPPED
  │    └─ 事件 RECOVERY_PAPER_STOPPED
  │
  └─ LIVE 策略:
       ├─ 校验 DB centerPrice/stepPct 非空
       ├─ slidingWindow.rehydrate 重建 slot 拓扑
       ├─ 从 grid_order LIVE 记录 recordOrderPlaced 挂回 slot
       ├─ 重订 WS + userStream.start
       ├─ ensureListenersRegistered
       ├─ fetchPositionRisk 塞回 state 持仓
       ├─ detectMissedFills (真实 vs grid_order 期望持仓)
       ├─ detectOrphanOrders (mm- 前缀但本地无记录)
       └─ 事件 RECOVERY_LIVE_OK + 告警项
```

---

## 五、配置字段（grid_strategy 表）

### 5.1 基础
| 字段 | 含义 |
|---|---|
| execution_mode | PAPER / LIVE |
| exchange | binance-usdcm |
| symbol | ETHUSDC |
| account_id | trd_exchange_account.id |
| leverage | 杠杆倍数（LIVE 会推给币安）|
| margin_type | ISOLATED / CROSS |
| hedge_mode | 1=双向, 0=单向 LONG |
| long_side_enabled / short_side_enabled | 分侧开关 |

### 5.2 网格几何
| 字段 | 默认 | 含义 |
|---|---|---|
| window_size_per_side | 5 | 每侧多少档 |
| qty_per_grid | (必填) | 每档数量 |
| base_step_pct | 0.5 | 基础步长% |
| minimum_step_safety_multiplier | 5.0 | 步长 ≥ spread × 此值 |
| adaptive_step_enabled | 1 | 是否启用 5min 步长调整 |

### 5.3 分层趋势判据
| 字段 | 默认 | 含义 |
|---|---|---|
| trend_layer1_interval | 5m | 主 K 线周期（AdaptiveScheduler 用）|
| trend_layer2_interval | 15m | L2 判据周期 |
| trend_layer3_interval | 1h | L3 |
| trend_layer4_interval | 4h | L4 |
| trend_layer2_adx_enter/exit | 25/20 | L2 ADX 进/出 |
| trend_layer3_adx_enter/exit | 30/22 | L3 |
| trend_layer4_adx_enter | 35 | L4（无自动 exit）|

### 5.4 Depletion 判据
| 字段 | 默认 | 含义 |
|---|---|---|
| depletion_pct_layer2 | 60% | 触发 L2 的 depletion 阈值 |
| depletion_pct_layer3 | 100% | L3 |
| depletion_time_layer2_sec | 1800 | 距上次反向 > 此时长 |

### 5.5 综合评分权重
| 字段 | 默认 |
|---|---|
| score_weight_adx | 3 |
| score_weight_depletion | 3 |
| score_weight_asymmetry | 2 |
| score_weight_consecutive | 1 |
| score_weight_reverse_gap | 1 |
| score_layer1/2/3/4_min | 3/5/7/9 |

### 5.6 风控阈值
| 字段 | 默认 | 含义 |
|---|---|---|
| long/short_reduce_pnl_pct | 1.0% | L3 减仓触发浮亏% |
| long/short_stop_pnl_pct | 3.0% | L4 停机触发浮亏% |
| account_emergency_pnl_pct | 5.0% | L5 账户熔断% |
| account_margin_usage_pct | 70% | 保证金使用率上限 |

### 5.7 梯队参数
| 字段 | 默认 | 含义 |
|---|---|---|
| ladder_pct_of_position | 30% | 梯队占累积仓位% |
| ladder_step_count | 5 | 分几档 |
| ladder_floor_at_avg_cost | 1 | 梯队最低价 ≥ 平均成本 |
| ladder_min_reserve_pct | 0.05% | 现价上方缓冲 |

### 5.8 冷却
| 字段 | 默认 | 含义 |
|---|---|---|
| reshuffle_price_move_grids | 5 | 建议 reshuffle 的 grid 数 |
| reshuffle_cooldown_sec | 300 | reshuffle 最短间隔 |
| min_mode_change_interval_sec | 300 | Layer 切换最短间隔 |
| layer3/4_min_hold_sec | 1800/3600 | Layer 最短保持时长 |

### 5.9 Paper 模拟
| 字段 | 默认 | 含义 |
|---|---|---|
| paper_queue_loss_factor | 0.5 | 排队损失系数 |
| paper_gtx_reject_rate | 0.05 | 模拟 GTX 拒单率 |
| paper_smart_pricing_enabled | 1 | SmartPricePlacer 开关（PAPER+LIVE 通用）|
| paper_min_liquidity_qty | 2.0 | 薄簿位阈值 |

### 5.10 紧急平仓
| 字段 | 默认 | 含义 |
|---|---|---|
| emergency_taker_fallback_enabled | 1 | ProactiveReducer escape hatch 允许 MARKET |
| emergency_taker_loss_threshold | 0.03 | 触发 escape 的浮亏阈值 |
| **risk_auto_stop_enabled** | **0** | **账户级 pnl 自动 emergencyStop 开关（V32 新加）；0=关(默认)，1=开。关闭时 `account_emergency_pnl_pct` 阈值不生效，仅币安 MARGIN_CALL 事件强制触发** |

---

## 六、实现完成度

### 已完成 ✅
- 双向网格 initialize + shiftWindow + rehydrate
- 成交 → CLOSE + rearm 满档
- Adaptive step/center 5min 调整
- LadderClose 梯队平仓（L2B 自动）
- Emergency 4 模式手动
- WS 私有流 + 3 个公有流全部 refCount + watchdog + 指数退避
- WsReconciliation 全量对账（跳过 PAPER）
- GridRecovery JVM 重启恢复 + positionRisk 对比 + 孤儿单检测
- SymbolFilters tick/step + minNotional
- BinanceTimeSync 15min 校准 + recvWindow=10000
- BinanceAccountClient dual/leverage/marginType 幂等预设
- fundingPnl 15min 拉取
- Paper 完整链路（fillSimulator + orderBook DB seed）
- Depletion fills-based 计算（不受 GTX 拒单误报）
- 内存权威 + 1min 批 sync DB
- 前端账户下拉 + realtime 字段 + 恢复按钮
- P&L 归因 arbPnl / dirPnl 分离

### 差距 ⚠️
1. **Layer 3/4 自动触发未接线**：`GridEngine.evaluateTrendScore` 只 CAS 层级，不主动调 `ProactiveReducer.reduce` 或 `SideStopService.stopSide`
2. **非对称步长未实现**：`stepPct` 单值，双侧同步；应改为 `longStepPct / shortStepPct` 分别由信号驱动
3. **rearm 未查 side_enabled**：`SlidingWindowManager.onOrderFilled` 生成 rearm intent 前应检查 `state.isLongSideEnabled()` / `isShortSideEnabled()`
4. **ATR 缓存无 TTL**：5min 重算，中间读到旧值不判断新鲜度（影响小）
5. **SmartPricePlacer 可能改变 slot 键**：若把价从档 N 调到 N+1，slot 拓扑与实际提交价不一致（罕见）
6. **架构上信号/执行未物理分离**：多个 scheduler 直接调 executor，未走"信号缓存 + 执行器订阅"模式
7. **仓位/资金硬上限未强制**：`max_position_qty` / `max_notional_usd` / `max_delta_qty` 三个字段
   在 schema 和 DTO 里存在，但**代码中从不拦截挂单**（仅 `max_notional_usd` 被 AccountRiskAggregator
   借用做"名义本金分母"计算浮亏比例）。**LIVE 上线前必须补护栏**，否则单边行情累积无限制，
   只能靠事后 Layer3/4/5 兜底（且 L3/L4 还没自动触发线）。
   推荐拦截点：
     - **A（首选，简单精准）**：`SlidingWindowManager.onOrderFilled` 生成 rearm intent 前判断
       `state.currentLongQty/shortQty + qtyPerGrid > max_position_qty` → 不 rearm，slot 留空
     - **B（safety net）**：`OrderClientRouter.normalize` 每笔 OPEN 前检查累积仓位是否会超限
     - **C（周期兜底）**：新增 `PositionLimitGuard` 30s scan，超 90% 上限即 `sideStop.stopSide` 分侧停机
   同时要在 UI 表单加校验：`qtyPerGrid × windowSize ≤ max_position_qty`（否则参数配置本身就
   保证会超限）+ `qtyPerGrid × centerPrice × windowSize × 2 ≤ max_notional_usd`

### 可选加固
- Layer 3/4 auto trigger（约 20 行）
- 非对称步长（约 100 行，改 GridState + AdaptiveStepCalculator + initialize/shift）
- rearm side_enabled 检查（3 行）
- ATR TTL（10 行）
- 信号-执行架构解耦（大重构，200+ 行）
- **仓位/资金硬上限强制**（约 40 行）—— **LIVE 前必做**：
  - `SlidingWindowManager.onOrderFilled` rearm 前检查 `currentLongQty + qty > max_position_qty` → 跳过
  - `OrderClientRouter.normalize` safety net：每笔 OPEN 前算累积仓位 + 名义金额 vs 上限
  - 前端 GridStrategyForm 加校验：`qtyPerGrid × windowSize ≤ max_position_qty`

---

## 七、REST API 一览

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | /admin/mm/grid | 策略列表 |
| GET | /admin/mm/grid/{id} | 策略详情（DB 快照）|
| POST | /admin/mm/grid | 创建 |
| PUT | /admin/mm/grid/{id} | 更新（runtime 字段被白名单拒绝）|
| DELETE | /admin/mm/grid/{id} | 删除（需先 stop）|
| POST | /admin/mm/grid/{id}/start | 启动 |
| POST | /admin/mm/grid/{id}/stop | 停止 SOFT |
| POST | /admin/mm/grid/{id}/emergency | 紧急停机（4 mode 可选）|
| POST | /admin/mm/grid/{id}/recover | 从 EMERGENCY_STOPPED 恢复 |
| GET | /admin/mm/grid/{id}/status | 实时状态（含内存 realtime 值）|
| GET | /admin/mm/grid/{id}/orders | 当前挂单 |
| GET | /admin/mm/grid/{id}/events | 事件流水 |
| GET | /admin/mm/grid/{id}/pnl | P&L 曲线 |
| POST | /admin/mm/grid/{id}/backtest | K 线回测 |

---

## 八、部署 & 运维

### 8.1 SQL 迁移
```
V29_mm_grid_module.sql       — 5 张表 + 菜单
V30_mm_paper_mode.sql        — Paper 字段
V31_mm_grid_order_audit_cols.sql — 补 create_by/update_by
V32_mm_grid_risk_optin.sql   — 加 risk_auto_stop_enabled 开关（默认关，opt-in）
CHECK_mm_columns.sql         — 诊断脚本
```

### 8.2 配置（application.yml）
```yaml
vertex:
  mm:
    enabled: true              # 总开关
    binanceUsdcmBaseUrl: https://fapi.binance.com
    binanceUserStreamWsBase: wss://fstream.binance.com/ws
    binancePublicWsBase: wss://fstream.binance.com/ws
    listenKeyKeepaliveSec: 1800
    wsReconnectInitialSec: 1
    wsReconnectMaxSec: 30
    reconcileMinIntervalSec: 60
    bookTickerCacheTtlMs: 5000
```

### 8.3 LIVE 上线 checklist
- [ ] `mysql < V29+V30+V31`
- [ ] `trd_exchange_account` 录 API key（需 Futures 交易 + 读权限，无提现）
- [ ] 账户已充 ≥ 20 USDC（minNotional 基线）
- [ ] PAPER 跑 24h 无 error 事件（`PERSIST_ORDER_FAILED / RECOVERY_FAILED`）
- [ ] LIVE 首启 qty=0.001（约 3 USDC/单）
- [ ] 24h 后确认 arbPnl 增长 + directional 接近 0
- [ ] 手动 emergency GRACEFUL 测试平仓 OK
- [ ] 加大 qty 前 kill -9 测试重启恢复

### 8.4 事件监控（前端事件流水 tab）
关键事件：
- `START / STOP / EMERGENCY_STOP / EMERGENCY_RECOVERED`
- `FILL / ARBITRAGE_ROUND_TRIP`
- `RESHUFFLE / STEP_ADJUST / SLOT_REFILL`
- `LAYER_CHANGE / LADDER_PLACE / LADDER_CANCEL`
- `LAYER3_REDUCE_START/END / LAYER3_TAKER_FALLBACK`
- `LAYER4_SIDE_STOP / MULTI_STRATEGY_LAYER3 / ACCOUNT_PNL_BREACH`
- `WS_DISCONNECT / WS_RECONNECT / RECONCILE_COMPLETE`
- `RECOVERY_LIVE_OK / RECOVERY_PAPER_STOPPED / RECOVERY_POSITION_MISMATCH / RECOVERY_ORPHAN_ORDERS`
- `PERSIST_ORDER_FAILED / GTX_REJECTED`
- `MARGIN_CALL`

---

## 九、后续路线图（按优先级）

### 短期（LIVE 上线前必做）
1. **Layer 3/4 自动触发**：GridEngine layer CAS 时调 `reducer.reduce` / `sideStop.stopSide`
2. **非对称步长**：分侧 step 由 depletion asymmetry 驱动
3. **rearm side_enabled 检查**：3 行小修

### 中期（LIVE 稳定后）
4. **信号-执行架构解耦**：SignalCache + Executor 模式
5. **Paper 撮合精化**：更真实的 queue loss（考虑挂单前排队量）
6. **Tick-level 回测**：下载 aggTrades 历史回放
7. **参数扫描 / A/B**：批量跑不同参数组合选优
8. **多策略 dashboard**：全账户视角

### 长期
9. **AI 参数调优**：DeepSeek/Gemini 分析近 N 天数据建议参数
10. **多品种 portfolio**：ETHUSDC + BTCUSDC + SOLUSDC 组合，配额分配
11. **跨交易所套利**：Binance + OKX + Bybit 同 symbol 价差捕获

---

## 十、附录：关键陷阱备忘

1. **PAPER JVM 重启** → PaperOrderBook 内存丢，DB 里 grid_order LIVE 记录必须清 CANCELED，否则手动 start 会双倍挂单（已修）
2. **Long 精度** → 前端 id 必须用 string，Number() 会丢精度（已修）
3. **DB 高频写** → `current_long_qty` 每 fill 写 DB 是反模式，应内存权威 + 1min batch（已修）
4. **@Transactional + 网络 I/O** → start/stop 移除 @Transactional，用短事务持久化（已修）
5. **listenKey 60min TTL** → 30min 主动 keepalive（已实现）
6. **币安 dualSideMode** → LIVE 首启必须设，否则 positionSide=LONG/SHORT 全拒 -4059（已实现）
7. **服务器时钟漂移** → BinanceTimeSync 15min 校准，所有签名用 timeSync.now() + recvWindow=10000（已实现）
8. **tick/step 违规** → SymbolFilters 全生成端 + Router safety net（已实现）
9. **GTX 拒单永久空档** → AdaptiveScheduler 5min refillEmptySlots 兜底（已实现）
10. **重启期间币安成交丢失** → GridRecoveryService.detectMissedFills 拉 positionRisk 对比（已实现）

---

*本文档反映的是当前实现状态（2026-09-05）。修改代码后请同步更新。*
