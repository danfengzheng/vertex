# 回测 2026-03-01 01:00 有买入信号、信号监控无信号 — 验证与分析

**现象**：回测在 2026-03-01 01:00、价格 0.280500 产生买入信号，但信号监控中没有对应买入信号。

以下从「当前配置」和「vertex.quote.subscription.mode=trade」两种情形分别说明是否会出现该情况，以及原因。

---

## 一、结论摘要

| 配置 | 是否可能出现「回测有信号、监控无信号」 | 主要原因 |
|------|----------------------------------------|----------|
| **当前配置**（admin-web 默认 `mode=kline`） | **有可能** | 见第二节：依赖 WS 是否在该 bar 收盘/下一 bar 到达时正常推送；断线、漏推会漏信号。 |
| **vertex.quote.subscription.mode=trade** | **更容易出现** | 见第三节：01:00 这根 bar 的「收盘」事件只有在**收到 02:00 时段内至少一笔成交**时才会发出；若无成交或断线，则不会发事件，信号监控永远不会为该 bar 跑策略。 |

---

## 二、当前配置下是否会出现（默认 mode=kline）

### 2.1 当前配置含义

- admin-web 中：`vertex.quote.subscription.mode` 默认来自 `VERTEX_QUOTE_SUBSCRIPTION_MODE`，未设置时为 **kline**（见 `web/admin-web/.../application.yaml` 注释与默认值）。
- 若未改过该配置，则当前为 **kline 模式**：1h 等周期直接订阅交易所 K 线流（如 `btcusdt@kline_1h`），不经过 trade 聚合。

### 2.2 信号监控何时会为「01:00 这根 bar」跑策略？

1. 策略引擎只在收到 **KLineEvent** 且事件中的 K 线被判定为「已收盘」时才会评估（默认 `onlyClosedKlines=true`）。
2. 事件来源是行情侧对「某根 K 线已收盘」的**一次通知**。

在 **kline 模式**下，这根 1h bar（openTime = 2026-03-01 01:00）的「收盘」通知来自：

- **路径 A**：交易所推送「01:00 这根 bar」且带 `closed=true` → 写入 Store → 发 KLineEvent（`KLineFlushOnNextHandler` 第 60–62 行：当前条 `closed=true` 则通知）。
- **路径 B**：交易所先推「01:00」未收盘，再推「02:00」的**任意一条**→ `KLineFlushOnNextHandler` 发现 openTime 从 01:00 变到 02:00，把**上一根（01:00）**设为 closed、入库并通知（第 48–56 行）。

因此，只要**路径 A 或路径 B 中任意一条发生**，就会有一次「01:00 bar 已收盘」的事件，策略引擎就会用 `triggeringKlineTime=01:00` 查库并评估，理论上会得到与回测一致的 BUY 信号。

### 2.3 当前配置下「会出现」该情况的典型原因

在**逻辑正确、数据一致**的前提下仍可能「回测有、监控无」的情况包括：

1. **事件根本没发生**
   - WS 在 01:00 收盘前后断线或长时间未收到 02:00 的第一条 1h 推送，导致既没有「01:00 closed=true」，也没有「02:00 首包」来触发 flush 上一根。
   - 则不会发任何「01:00 已收盘」的 KLineEvent，策略不会为该 bar 跑，**不会产生信号**。

2. **事件被过滤掉**
   - 若某次实现里把「未收盘」的 01:00 条发成了事件（例如 closed=false），而策略引擎 `onlyClosedKlines=true` 会过滤掉未收盘 K 线，则本次事件不会触发评估；若之后又没有补发「01:00 已收盘」事件，也会表现为该 bar 无信号。

3. **策略未订阅或未启用**
   - 该 exchange、symbol 在 2026-03-01 01:00 时没有已启用策略，或策略用的 interval 集合里没有 1h，则即使收到事件也不会为该 bar 跑（`usedIntervals.contains(interval)` 不通过）。

4. **数据与回测不一致**
   - 回测用的历史数据与当时实盘写入 Store 的数据不一致（缺 bar、时区或 openTime 不同），会导致同一 01:00 在回测里算出一笔 BUY，在实盘那一次评估时因窗口不同而得到不同结果（例如 NEUTRAL 或 SELL），看起来像「没有对应买入信号」。

因此：**按当前配置（kline 模式），在 WS 漏推/断线、事件被过滤、策略未跑或数据不一致等情况下，会出现「回测在 2026-03-01 01:00 有买入信号、信号监控没有」的情况。** 需要结合日志（是否在该时间点收到 1h 的 KLineEvent、是否执行了 runStrategy、triggeringKlineTime 是否为 01:00）和当时 WS 状态做验证。

---

## 三、vertex.quote.subscription.mode=trade 时是否会出现

### 3.1 trade 模式下的数据流

- `vertex.quote.subscription.mode=trade` 时，会启用 `InMemoryKLineAggregator`（`@ConditionalOnProperty(..., havingValue = "trade", matchIfMissing = true)`）。
- 日 K **以下**周期（如 1h）**不再订阅 kline 流**，改为订阅 **trade 流**（如 `btcusdt@trade`），由聚合器用逐笔成交在内存里聚合成 K 线。

相关代码：

- `BinanceWsDataSource.subscribe()`：若 `aggregator != null` 且为日 K 以下，则走 `subscribeTrade()`，不订阅 `kline_1h`。
- `InMemoryKLineAggregator.onTrade()`：对每个 interval 算 `openTime = (timeMs / interval.getMillis()) * interval.getMillis()`；当某笔成交的 `openTime` 与当前内存中的「当前根」不同时，先 **flush 上一根**（写入 Store + `notifier.notifyKLine(previousBar)`），再开新根。

因此：

- **01:00 这根 bar**（openTime = 2026-03-01 01:00:00.000 UTC）只会在**收到 02:00 时段内第一笔成交**时被 flush 并通知。
- 也就是说：**「01:00 bar 已收盘」的 KLineEvent 只有在「至少有一笔 trade 的 T 落在 [02:00:00.000, 03:00:00.000)」时才会产生。**

### 3.2 trade 模式下「会出现」的典型情况

1. **02:00 时段长时间无成交**
   - 若该交易对在 02:00:00 之后一段时间内没有任何成交（低流动性、节假日、极端行情等），则 01:00 的 bar 一直不会被 flush，**不会发 KLineEvent**，策略引擎不会为 01:00 跑，**必然没有对应买入信号**。
   - 直到 02:00 时段内出现第一笔成交，才会补发「01:00 已收盘」事件；若用户只看 01:00 附近的时间点，会认为「当时没有信号」。

2. **02:00 前断线或进程重启**
   - 若在 01:00~02:00 之间 WS 断线或进程重启，聚合器内存中的「01:00 当前根」丢失，且该 bar 尚未被 flush（因为还没收到 02:00 的成交），则**永远不会再有机会** flush 这根 01:00 bar。
   - 结果：Store 里没有这根 01:00 的已收盘 bar，也不会发事件，**信号监控永远不会为 2026-03-01 01:00 产生信号**，与回测不一致。

3. **时区/时间边界**
   - 若 2026-03-01 01:00 是本地时间而非 UTC，对应 UTC 可能是 00:00 或 02:00 等，则「下一根」的 openTime 会变，flush 时机也会变；逻辑同上，仍依赖「下一时段内有 trade」或「kline 模式下的下一根推送」。

因此：**在 vertex.quote.subscription.mode=trade 时，会出现「回测在 2026-03-01 01:00 有买入信号、信号监控没有」的情况，且比 kline 模式更常见**，因为：

- 依赖「下一时段（02:00）至少一笔成交」才会发出 01:00 的收盘事件；
- 断线/重启会直接丢失未 flush 的 01:00 bar，且无法补发。

---

## 四、如何验证是否属于上述情况

1. **确认实际运行时的 mode**
   - 查运行环境中的 `vertex.quote.subscription.mode` 或 `VERTEX_QUOTE_SUBSCRIPTION_MODE`：若为 `trade`，则优先从「02:00 是否有成交」「01:00~02:00 是否断线/重启」排查。

2. **查 2026-03-01 01:00 是否发过 KLineEvent（1h）**
   - 若有日志：在 02:00 前后（或 01:00 收盘后）是否有一条「KLine 已收盘」或 Flush/Aggregator 的日志，且 openTime=01:00、interval=1h。
   - 若没有，则要么没收到交易所推送（kline 模式），要么 02:00 没有成交/断线（trade 模式）。

3. **查策略是否在该时刻被触发**
   - 策略引擎日志中是否有 `runStrategy` / `processKLineUpdate`，且 `triggeringKlineTime` 或等价时间为 2026-03-01 01:00、exchange/symbol 与回测一致。

4. **查 Store 中是否有该 bar**
   - 对同一 exchange、symbol、interval=1h，查询 openTime=2026-03-01 01:00 的 K 线是否存在于 KLineStore；若 trade 模式下缺失，且 02:00 无成交或发生过重启，即可解释「监控无信号」。

5. **对比回测与实盘数据**
   - 用回测使用的同一段 K 线（含 01:00 及之前若干根）在本地再跑一次 `SignalGenerator.evaluate`，确认在 01:00 是否的确为 BUY；再对比当时实盘 Store 中 01:00 窗口的数据是否与回测一致。

---

## 五、总结表

| 项目 | 说明 |
|------|------|
| 回测有、监控无 | 在两种模式下都可能出现；trade 模式更易出现且原因更明确。 |
| **当前配置（默认 kline）** | 可能原因：WS 未在该 bar 收盘/下一 bar 到达时推送、事件被 onlyClosedKlines 过滤、策略未跑、或数据与回测不一致。 |
| **mode=trade** | 01:00 bar 的收盘事件**仅当 02:00 时段内至少有一笔成交**时才会产生；若 02:00 无成交或 01:00~02:00 断线/重启，则 01:00 不会发事件，**必然不会产生对应买入信号**。 |
| 建议验证 | 确认 mode、查 01:00 是否发过 KLineEvent、是否执行 runStrategy、Store 是否有 openTime=01:00 的 1h bar，并与回测数据对比。 |

若你提供：实际使用的 `vertex.quote.subscription.mode`、以及 2026-03-01 01:00~02:00 是否有断线/重启/无成交，可以进一步缩小到具体原因并给出改代码或配置的修改建议（例如 trade 模式下对「无成交」的补 flush、或定时 flush 当前根等）。
