# K 线改进方案可行性说明

## 目标方案简述

1. **Binance WS** → 订阅 **trade**（或 ticker）通道 → 拿到**最新成交价/逐笔成交**
2. **本地内存**自行按时间桶聚合，构建/更新各周期 K 线（M1, M5, M15, M30…）
3. **内存中的 K 线**作为**权威数据源**（当前根及近期已收盘根）
4. **缺失的历史 K 线**：先查 **RocksDB**，没有再通过 **REST** 拉取并回填

---

## 一、当前项目架构（简要）

| 模块 | 现状 |
|------|------|
| **Binance WS** | `BinanceWsDataSource` 订阅 **kline** 通道，按 `symbol@kline_1m` / `kline_5m` 等**多 topic 订阅**，直接拿到交易所已聚合的 K 线 |
| **K 线存储** | `KLineStore` → `RocksDBKLineStore`，按 `exchange:symbol:interval:openTime` 存，无内存“权威层” |
| **历史补全** | `StrategyDataWarmupService`、`QuoteSourceController` 已实现：RocksDB 不足时用 REST 拉历史并写入 RocksDB |
| **策略引擎** | `StrategyEngineService` 依赖 `KLineStore.query/getLatest` 和 `processKLineUpdate(KLine)`，只认 K 线模型，不关心来源 |

结论：**RocksDB + REST 回填**已具备；缺的是「用 trade 流 + 内存聚合」替代「多 kline 通道」并让内存成为权威源。

---

## 二、方案可行性结论：**可行**

### 1. Binance WS：trade 通道 → 本地聚合

- **通道**：Spot 使用 `btcusdt@trade`（逐笔成交），单 symbol 一个 stream 即可。
- **Payload**：`e=trade`，含 `p`(价格)、`q`(数量)、`T`(成交时间 ms)、`E`(事件时间) 等，足够做 OHLCV。
- **本地聚合**：按 `(symbol, interval)` 维护当前根；对每笔 trade 用 `openTime = floor(T / intervalMs) * intervalMs` 归属到对应根，更新 high/low/close/volume（及可选 quoteVolume、trades 计数）；根结束时落盘并通知。
- **多周期**：同一 trade 流可同时驱动 M1/M5/M15/M30/H1 等，每个 interval 独立时间对齐即可。

**结论**：用 trade 流 + 内存按时间桶聚合出 M1/M5/M15/M30… 在技术上**可行**，且与当前「多 kline 通道」相比，订阅数更少（每 symbol 一条流）。

### 2. 内存 K 线作为权威数据源

- **权威范围**：当前未收盘根 + 可选“最近若干已收盘根”放内存；更早历史只从 RocksDB/REST 读。
- **读路径**：  
  - `query`：先查内存（当前根 + 近期已关闭根），再查 RocksDB，缺段再 REST 回填后合并返回。  
  - `getLatest`：优先内存当前根，否则 RocksDB 最新一根。
- **写路径**：仅「根结束时」写 RocksDB + 发通知（与现有 `KLineStore.save` + `CompositeNotifier` 一致），策略引擎无需改接口。

**结论**：在现有 `KLineStore`/`IKLineService` 之上加一层「内存权威 + RocksDB + REST 回填」的读路径，**可行**，且对策略引擎透明。

### 3. RocksDB 补历史、REST 回填

- 项目已有：`RocksDBKLineStore`、`StrategyDataWarmupService`、`QuoteSourceController` 的 REST 回填逻辑。
- 改进后只需：**查询时**若 RocksDB 在请求时间范围内有缺口，再触发 REST 拉取并写入 RocksDB，再合并到本次返回结果。

**结论**：沿用现有 RocksDB + REST 能力即可，**可行**。

---

## 三、实现要点（供落地参考）

1. **Trade 数据源**  
   - 新增或扩展 Binance WS 数据源：订阅 `symbol@trade`，解析 `p/q/T/E`，输出统一 Trade 事件（symbol, price, quantity, timeMs）。

2. **内存聚合器**  
   - 组件：如 `InMemoryKLineAggregator` 或 `TradeToKLineAggregator`。  
   - 输入：Trade 事件。  
   - 内部：按 `(exchange, symbol, interval)` 维护「当前根」结构（openTime, open, high, low, close, volume, …）；收到 trade 时更新对应所有 interval 的当前根；若 `tradeTime` 跨过某 interval 的周期边界，则关闭该根、生成 `KLine`、调用 `KLineStore.save` + `notifier.notifyKLine`，并开启新根。  
   - 启动时：可从 RocksDB 读该 symbol+interval 的**最新一根已收盘**，以其 close 作为下一根的 open（或仅用第一笔 trade 的 price 作为 open），避免重启后第一根缺失。

3. **查询层（内存优先 + RocksDB + REST）**  
   - `KLineServiceImpl.query`：  
     - 先向内存聚合器要「当前根 + 近期已关闭根」（若配置了保留）；  
     - 再 `klineStore.query` RocksDB；  
     - 若时间范围在 RocksDB 有缺口，调用现有 REST 客户端拉取、`saveBatch` 写入 RocksDB，再合并到结果。  
   - `getLatest`：先问内存当前根，无则 `klineStore.getLatest`。

4. **与现有组件的衔接**  
   - 保留 `KLineStore`（RocksDB）和 `CompositeNotifier`；内存层只负责「生成 K 线」和「查询时优先返回」；落盘与通知仍走现有 save/notify。  
   - `BinanceWsDataSource` 可保留用于「仅订阅 kline 的兼容模式」，或逐步改为仅订阅 trade 并由聚合器产出 K 线；策略引擎仍通过 `processKLineUpdate` 收 K 线事件，无需改接口。

5. **ticker 与 trade 的取舍**  
   - 若方案中「ticker」指 24h 滚动统计，则**不适合**作为构建 M1/M5 等根的唯一输入（粒度粗、无逐笔）。  
   - 构建各周期 K 线、且内存为权威源，推荐以 **trade 流** 为准；ticker 仅作补充（如仅需最新价展示时可单独订阅）。

---

## 四、小结

| 项 | 结论 |
|----|------|
| Binance WS 使用 trade 流拿最新成交并本地聚合 M1/M5/M15/M30… | ✅ 可行 |
| 内存中维护当前根（及可选近期已关闭根）作为权威 | ✅ 可行 |
| 缺失历史先 RocksDB、再 REST 回填 | ✅ 已有基础，扩展查询链即可 |
| 与现有 RocksDB、REST、策略引擎、通知体系兼容 | ✅ 可行，接口可保持不变 |

**总体结论**：当前项目可以按「Binance WS trade → 内存聚合各周期 K 线 → 内存为权威；缺数从 RocksDB 补，RocksDB 没有再 REST 拉」的方案进行改造，技术上可行；建议以 **trade 流** 为主，ticker 仅作辅助（若需要）。

---

## 五、周期划分：日 K 以下用 trade 聚合，日 K 及以上只做 内存→RocksDB→REST

可以采用「按周期区分数据源」的策略，与当前枚举一致：

| 周期范围 | 包含周期 | 数据来源与补数顺序 |
|----------|----------|----------------------|
| **日 K 以下**（intraday） | M1, M3, M5, M15, M30, H1, H2, H4, H6, H12 | **Trade 流 → 内存聚合**，内存为权威；有缺失时：**内存 → RocksDB → REST** |
| **日 K 及以上** | D1, D3, W1, MN1 | **不**从 trade 流聚合；有缺失时：**内存（若有缓存）→ RocksDB → REST** |

### 设计要点

1. **日 K 以下（&lt; 1d）**
   - 仅对 `KLineInterval` 中周期 &lt; 1d 的项（M1～H12）做「trade → 内存聚合」。
   - 写入：根结束时写入 RocksDB 并通知；查询/缺数：先内存，再 RocksDB，再 REST 回填。
   - 实现上可在聚合器或数据源里按 `interval.getMillis() < 86_400_000L`（或 `interval != D1 && interval != D3 && interval != W1 && interval != MN1`）判断是否参与 trade 聚合。

2. **日 K 及以上（D1, D3, W1, MN1）**
   - **不**用 trade 流做本地聚合（数据稀疏、周期长，REST/交易所 kline 更合适）。
   - 来源可选其一或组合：
     - 保留现有 **Binance kline WS** 仅订阅 `@kline_1d` / `@kline_1w` 等，收到后直接 save + notify；或
     - 仅按需 **REST** 拉取，写入 RocksDB，需要时可加一层内存缓存（可选）。
   - 查询与补数统一走：**内存（若有）→ RocksDB → REST**，与日 K 以下共用同一套查询链，只是「内存」里没有 trade 聚合产出的日/周/月 K 线，仅可能来自 kline WS 或 REST 的缓存。

3. **统一查询链**
   - 不论周期，对外接口不变：`query` / `getLatest` 均按 **内存 → RocksDB → REST** 顺序解析；日 K 以下的内存由 trade 聚合填充，日 K 及以上的内存由 kline WS 或 REST 缓存（若实现）填充。

这样既保证「日 K 以下用 trade 聚合、内存权威」，又保证「日 K 及以上不依赖 trade、缺数仍按 内存→RocksDB→REST」。
