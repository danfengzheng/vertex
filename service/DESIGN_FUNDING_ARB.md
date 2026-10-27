# 资金费率套利模块设计文档（Funding Arb v2.1）

> **v2 变更说明**：v1 采用"结算前入场、结算后立平"的快进快出模型，经手续费成本核算后**必然亏损**。v2 改为**中低频波段模型**：跟随 funding regime 持续性入场、跨多次结算持有、动态出场。手续费被多次结算摊薄后才有 alpha 空间。
>
> **v2.1 补充**：接入 Open Interest / Long-Short Ratio / Basis 变化率 / Taker Buy-Sell Ratio / Realized Volatility 等多信号融合。历史 funding 单独用胜率仅 50-55%；多信号融合可提升到 60-65%，年化收益提升 30-50%。

---

## 0. 核心思想（v2 重写）

### 0.1 单次进出的手续费死结

一对完整套利（现货 + 期货，进 + 出共 4 笔单）的**不可避免成本**：

```
现货 taker: 0.10% × 2 (进 + 出)  = 0.20%
期货 taker: 0.05% × 2 (进 + 出)  = 0.10%
滑点：     0.025% × 4 (每笔单) = 0.10%
─────────────────────────────────
手续费小计:                    ≈ 0.40%
BNB 抵扣后:                    ≈ 0.30%
```

即使专门抓极端 funding，单次结算 funding 通常 0.05-0.15%，**永远打不平 0.30% 的进出成本**。

### 0.2 v2 模型：Funding Regime Trading

**核心 insight**：Funding 不是随机噪音，是**结构性趋势**：
- 牛市 FOMO 期间 BTC 全网正 funding 持续数天
- 山寨遭 pump 时 funding 拉高持续数小时到数天
- 熊市恐慌时全网负 funding 持续几天

**策略本质**：
- 押注"funding 会持续正/高"这段 regime
- 一次开仓、跨多次结算持有、regime 结束时平仓
- 手续费一次性成本，被 N 次结算的 funding 收入摊销

**收益公式**：
```
Net = ΣFundings（持仓期间累计） − TotalFee − BasisDelta − Slippage
```

只要 `ΣFundings ≥ 1.5 × TotalFee`，就有正 EV 空间。

### 0.2b 只做正 funding 场景（重要边界）⭐

**v2.1 明确限定：策略只入场 fundingRate > 0 的 symbol**。

- 正 funding = 多头付给空头 → 我们 SHORT perp 收 funding + LONG spot 对冲 delta ✓
- 负 funding = 空头付给多头 → 需要 SHORT spot 对冲，但 spot 无法裸空，需 margin 借币，成本与复杂度不成比例

**为什么放弃负 funding**：
- 借币做空 spot 需接入 Binance Margin API（现有代码没有）
- 借币利率浮动（3-15% APY）常侵蚀 funding 收益
- 借币池可能枯竭，操作不确定性大
- 保证金清算逻辑与合约账户不同，风控复杂
- 极端行情下（借入品种暴涨）容易被强制平仓

**历史数据支撑**：BTCUSDT 过去 12 月 funding 分布约 65% 时间为正、25% 为负、10% 接近零。**只做正 funding 已覆盖大部分机会**。全市场 300 个 symbol 扫描下，任何时候都有几十个正 funding 候选可选，机会不缺。

**负 funding 期的策略行为**：
- Scanner 自然筛不出候选（所有 symbol funding_ok filter 不通过）
- 策略进入"空转"状态：不开新仓，已开的按正常出场逻辑处理
- 事件日志记录 `NO_QUALIFIED_CANDIDATES`，前端 UI 显示"当前全市场负 funding 期，策略等待"
- 用户可选择手动停策略或让它继续空转（等 funding 反转）

**未来扩展方向**（不在 v2.1 范围）：
- 跨合约对冲（LONG USDC-M + SHORT USDT-M 或反之）—— 可覆盖 funding 差价机会，不依赖 spot
- Margin 借币做空 spot —— 覆盖负 funding 场景，复杂度高

### 0.3 与"网格"的定位对比

| 维度 | 网格（震荡策略） | Funding Arb（趋势策略） |
|---|---|---|
| 押注 | 价格震荡 | Funding regime 持续 |
| 单边行情 | 亏损 | 免疫方向（对冲） |
| 需要择时 | 需要（选区间） | 需要（选 regime） |
| 持仓周期 | 高频 fill（分钟）| 中低频（12h-7d） |
| Alpha 来源 | 震荡 vs 步长 | Funding regime 持续性 vs 手续费 |
| 心理压力 | 高 | 中（basis 波动可见但对冲）|

**核心差异**：网格赚"价格穿越 grid 的次数"，Funding Arb 赚"funding 持续偏高的时间"。都是对 market regime 的下注。

### 0.4 与 v1 的关键差异

| v1（快进快出）| v2（中低频波段） |
|---|---|
| 结算前 N 分钟入场 | Funding regime 判定后入场（与结算时刻无强绑定） |
| 结算后立即平 | Regime 结束/basis 保护/止盈触发平 |
| 单次 funding | 累计多次 funding |
| 每天多次入场（全市场轮换）| 每天 0-3 次入场，每次持仓多小时到数天 |
| 手续费占收入 60-90%（亏损） | 手续费占收入 10-30%（可盈利） |
| 年化预期 -20% ~ +10%（大概率负）| 年化预期 +8% ~ +30%（山寨精选） |

---

## 1. 术语与约定

- **funding regime**：某 symbol 在过去 N 次结算的 funding 分布特征（均值 / 方差 / 持续性）
- **regime 达标**：过去 N 次结算 funding 平均值 ≥ 阈值 且 达标比例 ≥ M/N
- **pair**：一对绑定的现货多头 + 期货空头持仓
- **持仓周期**：从 pair OPEN 到 pair CLOSED 的时长（可能跨越多个 fundingInterval）
- **regime 结束**：出场触发条件之一 —— funding 连续 N 次不达标
- **basis**：`(perp_mid_price − spot_mid_price) / spot_mid_price × 10000`（bps），正表示 perp 溢价
- **basis delta**：`current_basis − entry_basis`，衡量持仓期间基差变化

---

## 2. 系统架构

### 2.1 模块拆分

```
┌───────────────────────────────────────────────────────────┐
│                   FundingArb Module (v2)                  │
├───────────────────────────────────────────────────────────┤
│                                                           │
│  ┌───────────────────────┐   ┌────────────────────────┐   │
│  │ FundingHistoryTracker │◀──│ FundingRateSubscriber  │   │
│  │  (全市场历史 N 次)    │   │  (REST 30s + WS 实时)  │   │
│  └───────────────────────┘   └────────────────────────┘   │
│              │                                            │
│              ▼                                            │
│  ┌───────────────────────────────────────┐                │
│  │       RegimeScanner (每 1-5 min)      │                │
│  │  · 计算每 symbol 的 funding 均值/达标率│               │
│  │  · 过滤：均值 ≥ 阈值 且 达标率 ≥ M/N   │                │
│  │  · 过滤：24h volume / basis / 交集     │               │
│  │  · 排序：预期年化 or 累计 funding      │                │
│  └───────────────────────────────────────┘                │
│              │                                            │
│              ▼                                            │
│  ┌───────────────────────────────────────┐                │
│  │            PairExecutor                │                │
│  │  · 入场 saga（两腿同步）              │                │
│  │  · 出场触发引擎                        │                │
│  │  · 每次结算记账                        │                │
│  └───────────────────────────────────────┘                │
│              │                                            │
│              ▼                                            │
│  ┌────────────────────────────────────────┐               │
│  │        ExitTriggerEngine (每 tick)     │               │
│  │  · funding regime 反转                 │               │
│  │  · basis 保护                          │               │
│  │  · 止盈达成                            │               │
│  │  · 最长持仓时间到                      │               │
│  │  · 紧急事件（清算/公告）               │               │
│  └────────────────────────────────────────┘               │
│                                                           │
│  依赖：ExchangeAccount / BinanceTradeClient              │
│        UserDataStreamManager / BookTickerSubscriber       │
└───────────────────────────────────────────────────────────┘
```

### 2.2 与现有模块的边界（不变）

| 现有能力 | 复用 |
|---|---|
| ExchangeAccount 表 | 策略绑定 spot + perp 两个账户 ID |
| BinanceTradeClient | 现货 + 期货下单 |
| UserDataStreamManager | 双 stream 监听 order/account 事件 |
| BookTickerSubscriber | 两腿实时 bid/ask（basis 追踪）|
| SymbolFiltersCache | tick/step/minNotional 校验 |
| PaperOrderClient | 模拟撮合 |
| 菜单/权限/事件日志 | 完全复用现有模式 |

---

## 3. 账户与配置

### 3.1 账户绑定（同 v1）

策略配两个 accountId：`spot_account_id`（SPOT）+ `perp_account_id`（USDM）。可以是同源 API key 双记录，也可以完全独立。

### 3.2 杠杆

- Spot leg：现货余额买入，无杠杆（v1 版本不做 margin 借贷）
- Perp leg：可配 1x-10x，**推荐 2x**。理由：
  - 中低频持仓期间 basis 波动可能 ±0.5%，杠杆过高易触发保证金追加
  - 2x 保证金充足，还能保留 alpha
  - 隔离仓（ISOLATED）默认，防连锁

### 3.3 核心参数（相比 v1 全新）

**Regime 判定参数**：
- `lookback_settlements`：判定 regime 用过去多少次结算（默认 6，即约 48h）
- `min_avg_funding_bps`：过去 N 次平均 funding ≥ 此值（归一化到 8h 等效）
- `min_hit_ratio`：过去 N 次中至少 M/N 次达标（默认 4/6 = 67%）
- `min_latest_funding_bps`：最近一次 funding 不能突然反转（≥ 此值）

**出场触发参数**：
- `exit_regime_break_count`：连续 N 次不达标即平仓（默认 2）
- `exit_basis_delta_bps`：basis 反向变化超过此值立即平（默认 60 bps）
- `exit_take_profit_pct`：累计净收益达此值主动止盈（默认 0.8%）
- `exit_max_hold_hours`：最长持仓小时（默认 168 = 7 天）

**仓位参数**：
- `notional_per_pair`：单笔名义（USD）
- `max_open_pairs`：全策略最大并发对数
- `max_pairs_per_symbol`：单币种最大对数（默认 1，防超配）
- `perp_leverage`：期货杠杆
- `perp_margin_type`：ISOLATED / CROSS

**过滤参数**：
- `scan_all_market` / `symbol_whitelist` / `symbol_blacklist`
- `min_24h_volume_usd`：24h 成交额下限
- `max_entry_basis_bps`：入场时 basis 绝对值上限

**手续费参数**：
- `use_realtime_fee`：每次入场前调 tradeFee API
- `use_bnb_discount`
- `spot_fee_rate` / `perp_fee_rate`：静态兜底
- `min_expected_yield_pct`：预期净年化收益低于此值不入场（默认 8%）

**风控参数**：
- `max_drawdown_pct`：全策略回撤触发暂停
- `basis_emergency_bps`：极端 basis 熔断
- `funding_reversal_stop_days`：连续 N 天全市场 funding 反转则暂停策略

---

## 4. 信号采集与 Regime 判定（v2.1 重写）

### 4.1 信号可靠性说明

**历史 funding 单独作为信号能力有限**：
- 一阶自相关 0.3-0.5，三阶后基本 0
- 均值回归半衰期 1-3 次结算
- 极端 funding 常反映拥挤交易，追加仓 = 追高
- 只用历史 funding 判定 regime，回测胜率约 50-55%，扣手续费后接近零收益

**多信号融合能显著提升准确率**（回测经验 60-65% 胜率，年化提升 30-50%）。以下 7 个信号全部来自 Binance 公开 REST/WS，无需鉴权。

### 4.2 币安信号采集清单

| 信号 | Endpoint | 频率 | Weight | 数据形态 |
|---|---|---|---|---|
| Funding Rate 历史 | `/fapi/v1/fundingRate` | 每次结算 | 1 | 每 symbol 最近 1000 次 |
| Premium Index 实时 | `/fapi/v1/premiumIndex` | 30s | 1 | 全市场一次拉，含 markPrice/indexPrice/lastFundingRate/nextFundingTime |
| Premium Index K 线 | `/fapi/v1/premiumIndexKlines` | 每次结算 | 1 | Basis 历史 OHLC |
| Funding Info | `/fapi/v1/fundingInfo` | 每日 | 0 | 每 symbol 的 intervalHours / cap |
| Open Interest 实时 | `/fapi/v1/openInterest` | 5 min | 1 | 每 symbol 当前 OI |
| Open Interest 历史 | `/futures/data/openInterestHist` | 5 min | 1 | 每 symbol 最近 30 天 |
| 大户仓位多空比 | `/futures/data/topLongShortPositionRatio` | 5 min | 1 | 大户按仓位价值（最权威 LSR）|
| 主动买卖量比 | `/futures/data/takerlongshortRatio` | 15 min | 1 | 主动 buy/sell 成交量比 |
| K 线（波动率计算）| `/fapi/v1/klines` | 60 min | 1-5 | 每 symbol × 168 (1h × 7d) |

**Rate limit 估算**：全市场 300 symbol × 3 个 5min 级信号 = 900 请求 / 5min = ~3 req/秒，占 futures REST 限额 2400 weight/min 的约 5%。**完全无压力**。

### 4.3 SignalCollector 模块

新增 `FundingArbSignalCollector`，架构：

```
                每 30s     每 5 min      每 15 min    每 60 min    每次结算
                  │           │              │            │            │
                  ▼           ▼              ▼            ▼            ▼
        ┌──────────────┐ ┌─────────┐  ┌──────────┐ ┌─────────┐ ┌──────────────┐
        │ PremiumIndex │ │ OI+LSR  │  │ TakerBSR │ │ Klines  │ │ FundingRate  │
        │ Puller       │ │ Puller  │  │ Puller   │ │ Puller  │ │ Puller       │
        └──────────────┘ └─────────┘  └──────────┘ └─────────┘ └──────────────┘
                │           │              │            │            │
                └───────────┴──────────────┴────────────┴────────────┘
                                          │
                                          ▼
                            ┌──────────────────────────┐
                            │   SignalCache (per sym) │
                            │  · fundingHistory[100]  │
                            │  · oiHistory[288]       │
                            │  · lsrHistory[288]      │
                            │  · takerBsrHistory[96]  │
                            │  · basis (current + 288 pts) │
                            │  · realizedVol (24h/72h/7d) │
                            └──────────────────────────┘
                                          │
                                          ▼
                            ┌──────────────────────────┐
                            │  RegimeScanner (每 3min) │
                            │  多信号融合评分 + filter  │
                            └──────────────────────────┘
```

**内存开销**：每 symbol ~1.5 KB × 300 symbol = 500 KB，可忽略。

**持久化存储：RocksDB**（复用现有 `RocksDBManager`）

信号历史数据用 RocksDB，不用 MySQL。理由：
- 300 symbol × 96 pts/day × 180 天 ≈ 10M 记录，MySQL 需分表 / 索引维护，RocksDB LSM 天然适合
- 写模式是"追加式覆盖" —— 每 15 min 单线程 put，无并发冲突
- 读模式是"按 symbol 范围扫描" —— RocksDB range scan 天然对齐前缀
- RocksDB 支持 TTL 自动清理过期数据，无需 cron DELETE
- 项目已有 `RocksDBManager`（quote-service），`AiAnalysisStore` 是可参照模板，零新依赖

**Key schema（固定长度 byte 前缀 + big-endian ts，保证字典序 = 时间序）**：
```
farb:funding:{symbol:12}:{settleTs:8}   → JSON {rate, intervalHours}
farb:prem:{symbol:12}:{ts:8}            → JSON {markPrice, indexPrice, fundingRate, nextSettleTs}
farb:oi:{symbol:12}:{ts:8}              → JSON {openInterest}
farb:lsr:{symbol:12}:{ts:8}             → JSON {longShortRatio, longQty, shortQty}
farb:tbsr:{symbol:12}:{ts:8}            → JSON {buyVol, sellVol}
farb:vol:{symbol:12}:{ts:8}             → JSON {vol24h, vol72h, vol7d}
```

**存储层 API（对齐 `AiAnalysisStore` 模式）**：
- `save(prefix, symbol, ts, data)`
- `get(prefix, symbol, ts)` 精确点查
- `scanBySymbol(prefix, symbol, fromTs, toTs)` 范围扫描
- `scanAllSymbols(prefix, ts)` 某时点全市场快照
- `purgeExpired(prefix, olderThan)` 定期清理

**关系数据（策略配置 / 持仓 / 结算日志 / 事件日志 / 配置审计）走 MySQL**：需要事务、UI 多字段过滤、JOIN 查询，走关系型更合适。存储技术分工：
- **RocksDB**：时序信号 —— 高频写、按 symbol 范围读、TTL 自动清理
- **MySQL**：业务实体 —— 事务性、关系查询、UI 检索

### 4.4 归一化

所有 funding 换算到 8h 等效值：
```
funding_8h_eq = raw_funding × (8 / fundingIntervalHours)
```

用户配置的阈值（min_avg_funding_bps 等）也是 8h 等效值语义。

### 4.5 单信号计算方法

**A. Funding 趋势** —— 从 fundingHistory 计算
```
avg_recent    = mean(fundingHistory[-N:])              // 近 N 次均值（归一化后）
funding_momentum = mean(recent[-3:]) - mean(recent[-6:-3])  // 最近 3 次 vs 更前 3 次
hit_ratio     = count(r >= threshold) / N               // 达标率
latest        = fundingHistory[-1]
```

**B. Open Interest 趋势** —— 从 oiHistory 计算
```
oi_current    = latest OI
oi_change_2h  = (current - oi_2h_ago) / oi_2h_ago
oi_change_24h = (current - oi_24h_ago) / oi_24h_ago

信号:
  oi_change_2h > +5% + funding_up   → 新钱进场，趋势延续 ✓ (BULLISH)
  oi_change_2h < -3% + funding_up   → 空头离场，可能顶部 ⚠ (TOPPING)
  oi_change_24h > +30%              → 严重拥挤，回避 ✗ (CROWDED)
```

**C. Long/Short Ratio** —— 从 topLongShortPositionRatio
```
lsr = long_qty / short_qty (大户按仓位价值)

反向指标（极端 = 反转前兆）:
  lsr > 3.0        → 多头极度拥挤，避免做多入场（EXTREME_LONG）
  lsr < 0.4        → 空头极度拥挤，避免做空入场（EXTREME_SHORT）
  0.7 ≤ lsr ≤ 2.0 → 相对均衡，可正常入场（BALANCED）
```

**D. Basis 变化率** —— 从 premiumIndex + premiumIndexKlines
```
basis_current = (markPrice - indexPrice) / indexPrice × 10000  (bps)
basis_2h_ago  = basis from klines 2h back
basis_delta_2h = basis_current - basis_2h_ago

信号:
  basis_delta_2h > +10 bps → 溢价快速扩大，下次 funding 会跳升 ✓
  |basis_current| > 50 bps → 已经过高，入场溢价大风险高 ✗
```

**E. Taker Buy/Sell Ratio** —— 从 takerlongshortRatio
```
buySellRatio = 主动 buy 量 / 主动 sell 量

信号:
  ratio > 1.3 持续 4h+   → 买盘强势，funding 可能持续正
  ratio < 0.7 持续 4h+   → 卖盘强势，funding 可能翻负
  与 funding 同向         → 确认信号 (CONFIRMED)
  与 funding 背离         → 警告，可能反转 (DIVERGENCE)
```

**F. Realized Volatility** —— 从 1h Klines
```
returns = log(close[t] / close[t-1])
vol_24h = std(returns[-24:]) × sqrt(24 × 365)
vol_72h = std(returns[-72:]) × sqrt(24 × 365)

信号:
  vol_24h / vol_72h > 1.5 → 波动率骤升，regime 转换加速 → 缩短 max_hold + 收紧 basis 保护
  vol 平稳                → 可放宽 hold 时间
```

**G. Cross-symbol 共振** —— 基于 BTC baseline
```
correlation = corr(BTCUSDT_funding_series, symbol_funding_series)  (滚动 30 次结算)
predicted   = BTC_latest_funding × correlation
deviation   = symbol_latest_funding - predicted

信号:
  |deviation| > threshold → symbol 有独立事件驱动（可能真 alpha）
  |deviation| ≈ 0        → 只是跟 BTC 共振，收益低
```

### 4.6 多信号融合 Regime 判定

分为**必要条件（filter）**和**评分条件（score）**两阶段：

**Stage 1 — 硬 filter（任一不通过则排除）**
```
funding_ok        : avg_recent_funding_8h_eq >= min_avg_funding_bps   (必须 > 0)
                    AND hit_ratio >= min_hit_ratio
                    AND latest >= min_latest_funding_bps               (最近一次也 > 0)
                    AND avg_recent > 0                                 (§0.2b：只做正 funding)

lsr_ok            : 0.4 <= lsr <= 3.0

oi_ok             : NOT (oi_change_24h > 30% AND funding_up)  
                    (排除极端拥挤)

basis_ok          : |basis_current| <= max_entry_basis_bps

vol_ok            : vol_24h / vol_72h <= 2.0  
                    (排除极端波动突发)

volume_ok         : 24h_notional >= min_24h_volume_usd

blacklist_ok      : symbol NOT IN blacklist
```

**Stage 2 — 综合评分（filter 通过后排序）**
```
symbol_score(sym) =
    0.40 × normalize(avg_recent_funding × oi_multiplier)     # funding 主信号
  + 0.15 × normalize(funding_momentum)                       # funding 趋势加速度
  + 0.15 × normalize(basis_change_rate)                      # basis 领先指标
  + 0.15 × reverse(lsr_extremeness_score)                    # LSR 反向权重
  + 0.10 × sign(taker_bsr - 1) × confidence(taker_bsr)       # taker 确认
  + 0.05 × (deviation_from_btc > 0 ? bonus : 0)              # cross-symbol 独立

where oi_multiplier = 1.2 if oi_change_2h > 5%
                    = 1.0 if -3% <= oi_change_2h <= 5%
                    = 0.5 if oi_change_2h < -3%
```

**Stage 3 — 排序 + 入场队列**
```
排序 symbol_score desc
取 top K (受 max_open_pairs 限制)
每个入场前再做 preflight check（basis / 资金 / 精度对齐）
```

### 4.7 用户可配置的信号权重

不同市场环境下最优权重不同，UI 提供可调：

| 参数 | 保守 | 平衡 | 激进 |
|---|---|---|---|
| lookback_settlements | 12 | 6 | 3 |
| min_avg_funding_bps | 0.06% | 0.04% | 0.02% |
| min_hit_ratio | 5/6 | 4/6 | 3/6 |
| lsr_max_extreme | 2.5 | 3.0 | 4.0 |
| oi_crowding_threshold | 20% | 30% | 50% |
| vol_spike_multiplier | 1.5 | 2.0 | 3.0 |
| require_taker_confirm | true | false | false |

**保守配置**：只入场极强共振信号（funding + OI + LSR + basis 都利好）→ 少交易但高胜率  
**激进配置**：单一信号达标即入场 → 多交易但胜率低

### 4.8 信号可视化（前端）

策略详情页新增"信号面板"：
- 全市场 top 20 候选表：symbol + 各信号打分 + 综合 score + 状态（QUALIFIED / SKIPPED_原因）
- 单 symbol drilldown：funding 趋势线 + OI 曲线 + LSR 曲线 + basis 曲线（4 图叠加）
- 已入场 pair 的实时信号追踪：入场时快照 vs 当前值对比

---

## 5. 扫描节奏与触发

### 5.1 三层扫描频率

- **可用交集刷新**：每 1 小时（现货 + 期货 symbol 交集 + 24h volume 过滤）
- **Funding regime 扫描**：每 1-5 分钟（默认 3 min，按用户配置）
- **持仓 tick 检查**：每 30 秒（每个 OPEN pair 检查出场触发条件）

### 5.2 入场触发（新）

不再与结算时刻强绑定。**任何 regime 扫描 tick 都可能触发入场**：

```
每 3 min tick:
  for each symbol in 可用交集:
    regime = calculateRegime(symbol)
    if not regime.is_qualified: skip
    if 该 symbol 已有 OPEN pair: skip (受 max_pairs_per_symbol 限制)
    if 全策略 open pairs ≥ max_open_pairs: skip
    
    basis = calculateCurrentBasis(symbol)
    if |basis| > max_entry_basis_bps: skip
    
    expected_yield = projectYield(regime, basis, fees)
    if expected_yield < min_expected_yield_pct: skip
    
    → 加入本轮入场队列
  
  按 expected_yield 降序取前 K 个入场
```

**关键区别**：v1 是"哪个币下次结算前 N 分钟"触发；v2 是"哪个币现在 regime 达标"触发。

### 5.3 出场触发（新）

每 30s 对每个 OPEN pair 检查五类条件：

1. **Regime 反转**：过去 exit_regime_break_count 次结算 funding 均 < 阈值 → 平仓
2. **Basis 保护**：`|current_basis - entry_basis| > exit_basis_delta_bps` → 平仓（保护性止损）
3. **止盈**：累计 net_pnl / notional ≥ exit_take_profit_pct → 平仓（锁利）
4. **最长持仓**：`now - entry_time > exit_max_hold_hours` → 强制平（避免长期基差风险）
5. **紧急事件**：币安公告下架 / 保证金不足 / 用户手动 → 平仓

出场触发优先级从上到下，任一命中即执行。事件日志记录触发原因。

### 5.4 结算记账（每次结算都记）

每个 OPEN pair 在每次结算时刻附近监听 UserDataStream ACCOUNT_UPDATE：
- 若 income type = FUNDING_FEE 且属于本 pair 的 perp 头寸 → append 到 pair.settlement_log
- 累计 pair.actual_funding_pnl += this_settlement_income
- 更新 pair 显示"距上次 funding N h，累计 XX USD"

---

## 6. 入场逻辑（重构）

### 6.1 前置校验

在 `PairExecutor.openPair` 里，比 v1 更严格：

1. **Regime 二次校验**：入场前重新算一次 regime，确认仍达标（防扫描到下单的间隔翻转）
2. **Basis 二次校验**：`|basis| ≤ max_entry_basis_bps`
3. **仓位上限**：max_open_pairs / max_pairs_per_symbol
4. **资金充足**：spot 余额 + perp 保证金
5. **杠杆已设**：调用 setLeverage（有 cache）
6. **两腿 qty 精度对齐**：round 后差异 <1%
7. **手续费实时查询**（可选，1h cache）
8. **预期年化再校验**：
   ```
   projected_next_settlement_pnl = notional × avg_funding_8h_eq × (intervalHours/8)
   projected_holds = expected_regime_length / intervalHours  (根据历史 regime 持续时长估算)
   projected_total_funding = projected_next_settlement_pnl × projected_holds
   projected_net = projected_total_funding − total_fee − expected_slippage
   projected_annualized_yield = projected_net / notional × (365 × 24 / expected_regime_hours)
   
   if projected_annualized_yield < min_expected_yield_pct: skip
   ```

### 6.2 Saga 两腿下单（同 v1）

同 v1 §6.2 Saga 补偿模式：
- 并发提交两腿 MARKET 单
- 都成功 → 记账；一腿失败 → 立即回滚已成功那腿
- 超时 → REST 查真实状态决定后续

**订单类型选择**：
- **v2.1 当前实现**：入场/正常出场 全用 **IOC LIMIT**（价格 = best_ask × 1.001 for BUY / best_bid × 0.999 for SELL），等价 MARKET 但限制最坏滑点 ≤ 0.1%
- **紧急平仓** (basis breach / margin call / saga 回滚)：全 MARKET fire-and-forget，速度优先
- **未来优化方向**（v3 迭代）：Perp 腿改 LIMIT + GTX post-only（省 0.02%/单 maker fee），Perp fill 后 spot 再 IOC 匹配，超时 fallback。预期净成本从 0.30% → 0.26%（改善 ~13%），但同步风险 + 复杂度上升，Phase 3 稳定后再考虑
- **Spot 侧不做 maker 优化**：VIP 0 时 spot maker/taker 同价 0.10%，无收益。VIP ≥ 1 后可考虑同 perp 相同 LIMIT-first 策略

### 6.3 入场记账

写入 pair 记录：
- symbol / entry_time / entry_spot_price / entry_perp_price
- entry_basis_bps / **entry_regime_snapshot**（近 N 次 funding 数组）
- projected_regime_hours / projected_net_yield
- spot_qty / perp_qty
- entry_spot_fee / entry_perp_fee
- entry_spot_order_id / entry_perp_order_id
- status = OPEN
- next_expected_settlement_time = 该 symbol 的 nextFundingTime

**没有** v1 的 "single_settlement_target"，因为可能跨多次结算。

---

## 7. 持仓管理（新章节）

### 7.1 生命周期

```
[OPEN] ──每次结算──> settlements_log 累加 funding_pnl
   │
   │──每 30s tick──> ExitTriggerEngine 检查
   │
   │─┬─ regime break     ─┐
   │ ├─ basis breach     ─┤
   │ ├─ take profit hit  ─┤─> [CLOSING] ──saga── > [CLOSED]
   │ ├─ max hold reached ─┤
   │ └─ manual/emergency ─┘
```

### 7.2 每次结算的处理

当 UserDataStream 推送某 pair 的 FUNDING_FEE income：
- 追加到 `fund_arb_settlement_log`：`(pair_id, settlement_time, funding_rate, funding_amount)`
- 更新 pair.actual_funding_pnl += income
- 更新 pair.last_settlement_time
- 触发一次 ExitTriggerEngine 检查（结算刚发生，是重新评估好时机）

### 7.3 Basis 实时追踪

每个 OPEN pair 订阅两腿的 BookTicker：
- 每秒计算 `current_basis = (perp_mid − spot_mid) / spot_mid × 10000`
- 与 `entry_basis` 对比，得 `basis_delta`
- 若 `|basis_delta| > exit_basis_delta_bps` → 立即触发出场
- 若 `|basis_delta| > basis_emergency_bps` → **紧急 MARKET 平**（不走 saga，最快速度出）

### 7.4 累计 P&L 显示

前端详情页每 pair 实时展示：
- **Funding 收入**：`actual_funding_pnl`
- **未实现方向盈亏**：`(current_spot - entry_spot) × spot_qty - (current_perp - entry_perp) × perp_qty`
- **已扣手续费**：entry 两腿的
- **预估平仓成本**：exit 两腿手续费估算
- **净预期**：以上四项汇总

---

## 8. 出场逻辑详解

### 8.1 五种出场触发

**A. Regime 反转触发**（最常见）
```
recent = getRecentFundings(pair.symbol, exit_regime_break_count)
if all(r < min_avg_funding_bps for r in recent_normalized):
    trigger = REGIME_BREAK
```

**B. Basis 保护触发**
```
basis_delta = current_basis - pair.entry_basis
if |basis_delta| > exit_basis_delta_bps:
    if basis_delta 对当前仓位不利:
        trigger = BASIS_BREACH
    else:
        trigger = BASIS_BREACH_LUCKY  (有利变动也可考虑止盈)
```

**C. 止盈触发**
```
total_net_pnl = pair.actual_funding_pnl 
             + unrealized_direction_pnl 
             - total_fees_so_far
             - projected_exit_fees
if total_net_pnl / pair.notional ≥ exit_take_profit_pct:
    trigger = TAKE_PROFIT
```

**D. 时间上限触发**
```
if now - pair.entry_time > exit_max_hold_hours:
    trigger = MAX_HOLD_TIMEOUT
```

**E. 紧急事件触发**
- 保证金率警戒线
- 币安下架公告
- 全策略回撤达标
- 用户手动"立即平仓"按钮
- 单腿被强平（另一腿必须立即市价平）

### 8.2 两腿平仓 Saga

同 v1 §8.2：
- 并发下 spot SELL + perp BUY（reduceOnly=true）
- 一腿失败 → 重试 3 次
- 三次仍失败 → 高优告警"僵尸仓"人工处理

**优化**：Basis 紧急触发（emergency）时**不用 saga**，直接双腿 MARKET 单一起 fire-and-forget，追求速度。

### 8.3 P&L 完整归因

平仓后写入 pair.net_pnl：
```
funding_pnl = pair.actual_funding_pnl   (累计所有结算)
direction_pnl = (exit_spot - entry_spot) × spot_qty 
              - (exit_perp - entry_perp) × perp_qty
              (理论上 ≈ 0，delta 中性；实际有 basis 引起的偏差)
fee_total = entry_fees + exit_fees   (两腿进出共 4 笔)
slippage_impl = 实际成交价 vs 挂单时 mid 的差
net_pnl = funding_pnl + direction_pnl - fee_total  

basis_pnl = direction_pnl  (等价视角：basis 变动即方向 pnl)
```

写入 pair.funding_pnl / basis_pnl / fee_paid / net_pnl / close_trigger（枚举）/ close_time。

---

## 9. 风控体系（增强 v1）

### 9.1 三层保护

**Layer 1: 单 pair**
- Basis 保护（exit_basis_delta_bps）
- Basis 紧急熔断（basis_emergency_bps）→ 立即市价平
- 保证金率警戒 → 触发提前平

**Layer 2: 策略级**
- 全策略回撤 max_drawdown_pct 触发暂停 + 平所有 pair
- 单币种上限（max_pairs_per_symbol）
- 全市场 funding 反转（连续 N 天所有主流币 funding 转负）→ 策略进入 HIBERNATE 状态

**Layer 3: 账户级**
- 复用 AccountRiskAggregator（同网格）
- 保证金使用率超限暂停开新仓
- 币安 MARGIN_CALL 事件强制紧急平仓

### 9.2 熔断按钮

前端详情页三级熔断：
- **暂停新入场**（保留现有 pair 继续运行）
- **正常平所有仓**（走 saga）
- **紧急平所有仓**（双腿一起 fire-and-forget，最快速度）

### 9.3 黑名单动态学习

某币若出现下列情况自动加入 7 天黑名单：
- Saga 回滚 2 次以上
- Basis 熔断触发
- 平仓时严重滑点（> 0.5%）
- 币安异常事件

黑名单期间 RegimeScanner 直接跳过，UI 可见并可手动移除。

---

## 10. Paper 模拟（同 v1，微调）

### 10.1 双腿撮合模拟

复用 PaperOrderClient + FillSimulator。现货 MARKET 立即按 ask 成交，期货 SELL 按 bid 成交。

### 10.2 Funding 结算 Mock

每 30s 检查每个 OPEN PAPER pair：
- 若 now ≥ pair.next_expected_settlement_time：
  - 拉真实 REST premiumIndex 获取该 symbol 的 lastFundingRate
  - `mock_income = perp_qty × mark_price × real_funding_rate`
  - 更新 PaperAccountState 余额
  - 生成假 FUNDING_FEE income event
  - append 到 settlement_log

用真实 fundingRate 而非模拟值，保证 PAPER 结果贴近 LIVE。

### 10.3 Basis 变化模拟

Basis 用真实市场数据（BookTicker），只有 fill / fee 是模拟的。

---

## 11. 崩溃恢复

### 11.1 状态枚举

pair.status 可能是：
- **PENDING_ENTRY**：两腿下单中崩溃
- **OPEN**：正常持有中
- **CLOSING**：出场触发后两腿平仓中崩溃
- **CLOSED**：完成
- **EMERGENCY_CLOSED**：紧急平仓完成
- **ZOMBIE**：saga 失败无法自动恢复，等待人工

### 11.2 恢复流程

LIVE:
- 扫 status ∈ {PENDING_ENTRY, OPEN, CLOSING} 的 pair
- **PENDING_ENTRY**：REST 查两腿 orderId 状态 → 都成 → 转 OPEN；有一腿失败 → 回滚已成功那腿 → CANCELED
- **OPEN**：正常恢复，rebuild ExitTriggerEngine 状态；查币安 income 补齐缺失的 settlement_log 记录
- **CLOSING**：REST 查两腿平仓状态 → 都完成 → CLOSED；失败 → 重试
- 拉两腿真实持仓 vs 本地 pair.qty 校对，检测漂移

PAPER:
- 内存丢失 → 所有 pair 强制标 CANCELED，策略 → STOPPED

### 11.3 崩溃期间的结算怎么办

若崩溃时正好错过结算事件：
- 恢复后主动查 REST `/fapi/v1/income?startTime=<lastRecoveryTime>` 拿所有 FUNDING_FEE
- 按 pair 的 perp_qty / 时段匹配到对应 pair
- 补记 settlement_log

---

## 12. 与网格并存

同 v1 §12：账户隔离推荐，API rate limit 互不冲突，交易冲突在 V1 阶段不存在。

---

## 13. 观测

### 13.1 前端页面

**列表页**：
- 策略状态、开仓对数、累计 net_pnl、24h 净收益、当前平均年化

**详情页**（v2 大改）：
- 顶部：累计 P&L / 手续费占比 / 平均持仓时长 / 命中率
- **Funding Regime 面板**：候选池 top 20 币的当前 avg funding / hit ratio / expected annual yield
- **Open Pairs 面板**：每 pair 显示 entry_time / 持仓时长 / 累计 funding_pnl / 当前 basis_delta / 距下次结算 / 预估净收益 / 触发出场原因（若近）
- **历史成交面板**：所有 CLOSED pair，含 close_trigger 分类统计
- **P&L 曲线**：equity curve
- **单币种表现**：按 symbol 分组统计年化 / 平均持仓 / 出场原因分布

**配置页**：
- Regime 参数（lookback / threshold / hit_ratio）
- 出场触发参数（regime_break / basis / take_profit / max_hold）
- 仓位参数
- 手续费与风控

### 13.2 事件类别（v2 补充）

- SCAN_TICK / REGIME_QUALIFIED / REGIME_DISQUALIFIED
- CANDIDATE_ENTERED / CANDIDATE_SKIPPED（原因分类）
- PAIR_OPEN_ATTEMPT / OPEN_SUCCESS / OPEN_ROLLBACK
- SETTLEMENT_RECEIVED（每次结算触发）
- EXIT_TRIGGER_FIRED（触发原因）
- PAIR_CLOSE_ATTEMPT / CLOSE_SUCCESS / CLOSE_ZOMBIE
- BASIS_ALERT / BASIS_EMERGENCY
- REGIME_BREAK / TAKE_PROFIT / MAX_HOLD_TIMEOUT
- ORPHAN_ORDER
- RECOVERY_*

---

## 14. 回测（v2.1 支持信号 A/B 测试）

### 14.1 数据准备

**多信号版本回测需要拉的历史数据**：

| 数据 | Endpoint | 时间跨度 | 粒度 |
|---|---|---|---|
| Funding Rate | `/fapi/v1/fundingRate` | 6-12 个月 | 每次结算 |
| Premium Index Klines | `/fapi/v1/premiumIndexKlines` | 6-12 个月 | 1h |
| Klines（现货 + 期货）| `/api/v3/klines` + `/fapi/v1/klines` | 6-12 个月 | 1h（basis + vol 计算）|
| Open Interest 历史 | `/futures/data/openInterestHist` | Binance 限制**最多 30 天**⚠ | 5-15 min |
| Long/Short Position Ratio | `/futures/data/topLongShortPositionRatio` | 同上 **最多 30 天** ⚠ | 5-15 min |
| Taker Buy/Sell Ratio | `/futures/data/takerlongshortRatio` | 同上 **最多 30 天** ⚠ | 15 min |
| 24h volume | `/fapi/v1/ticker/24hr` | 需自建每日 snapshot | 每日 |

**关键限制**：OI/LSR/Taker 数据 Binance 只保留 30 天。**必须自建定时任务**每小时拉取一次并落库，长期积累才能做多信号回测。

**过渡方案**：
- Phase 1 冷启动阶段：只用 funding + basis + vol（都可回溯 6-12 月），先做单信号回测
- 项目上线后主动收集 OI/LSR/Taker 数据，累积 3-6 个月后再做完整多信号回测

### 14.2 回测引擎

按小时步进遍历，与实盘引擎共享同一 `RegimeScanner` + `ExitTriggerEngine` 代码，只是"当前时点"改为回测时点：

```
for each hour t in history:
  # 更新所有 signal cache 到时间 t（模拟"如果当时策略在跑，能看到什么"）
  refresh signalCache from history at time t
  
  # 每 3 min 触发一次 regime scan
  if t % 3min == 0:
    for each candidate symbol:
      regime = RegimeScanner.evaluate(symbol, at t)   # 与实盘同一代码路径
      if not regime.qualified: skip
      if 已经 OPEN 过此 symbol 未平: skip
      → 模拟入场 at t（含手续费 + 滑点建模）
  
  # 每 30s 触发一次持仓 exit check
  if t % 30s == 0:
    for each OPEN pair:
      exit_trigger = ExitTriggerEngine.check(pair, at t)
      if exit_trigger: 模拟出场 at t
      elif 遇到结算时刻: 累加 funding_pnl（用真实历史 fundingRate）
```

**关键**：回测引擎与实盘引擎**共享同一决策代码**，回测跟 PAPER 一致性高。回测独有的是"时间模拟" + "手续费/滑点建模"。

### 14.3 信号组合 A/B 测试

配置文件可切换不同信号组合，跑同一段历史数据比对结果：

**组合 A：只用历史 funding**
```
required: funding_ok
score: 100% funding_avg
```
预期：胜率 50-55%，年化 3-8%

**组合 B：funding + basis**
```
required: funding_ok + basis_ok
score: 60% funding_avg + 40% basis_change
```
预期：胜率 55-58%，年化 8-12%

**组合 C：funding + basis + OI**
```
required: funding_ok + basis_ok + oi_ok
score: 50% funding + 20% basis + 30% oi
```
预期：胜率 58-62%，年化 12-18%

**组合 D（完整）：五信号融合**
```
required: funding_ok + basis_ok + lsr_ok + oi_ok + vol_ok
score: 40% funding + 15% momentum + 15% basis + 15% LSR + 10% taker + 5% cross
```
预期：胜率 60-65%，年化 15-25%

**输出对比表**：
```
组合   胜率    盈亏比   年化    最大回撤   平均持仓h   Sharpe
A      52%    1.15     6.5%    18%        14         0.42
B      56%    1.28     10.8%   14%        18         0.68
C      60%    1.42     15.2%   11%        22         0.95
D      63%    1.55     19.7%   9%         26         1.28
```

数据显示：**每加一个正交信号，Sharpe 提升约 0.2-0.3**。收益递减但风险改善明显。

### 14.4 输出指标

- 年化收益 / Sharpe / 最大回撤
- 平均持仓时长
- 平均 pair 净收益（USD 和 %）
- 出场触发分布（regime_break / basis / take_profit / max_hold 占比）
- 单币种年化 top 20 / bottom 20
- Funding 反转导致亏损的 case 分析
- **各信号贡献度**（feature importance）：把某信号权重设为 0，跑一遍，看年化下降多少
- **胜率随时间变化曲线**（识别 alpha 衰减）

### 14.5 参数调优避坑

- **train/test 分离**：前 70% 时间 tune 参数，后 30% out-of-sample 验证
- **多市场分开**：BTC 家族 / 主流山寨 / memecoin 分别调优（每类最优参数不同）
- **手续费假设保守**：VIP 0 + BNB 抵扣，避免 overfitting 到低费用
- **警惕过拟合**：如果参数组合极多（例如 5 信号 × 每信号 3 阈值 = 243 组合），要用 walk-forward 或 cross-validation

### 14.6 长期数据自建

由于 Binance OI/LSR/Taker 只留 30 天，**建议策略上线前先跑一个纯数据收集服务**：

```
FundingArbDataCollector （独立于策略运行）:
  · 每 15 min 拉全市场 OI / LSR / Taker
  · 存 InfluxDB / TimescaleDB / 或 MySQL 分表
  · 累积 3-6 个月后作为完整回测基础
```

这个数据收集器**可以现在就跑起来**，不需要等策略实现完成。等策略实现好时正好有半年历史数据用于回测。

### 14.3 输出指标

- 年化收益 / Sharpe / 最大回撤
- 平均持仓时长
- 平均 pair 净收益（USD 和 %）
- 出场触发分布（regime_break / basis / take_profit / max_hold 占比）
- 单币种年化 top 20 / bottom 20
- Funding 反转导致亏损的 case 分析

### 14.4 参数调优避坑

- **train/test 分离**：前 70% 时间 tune 参数，后 30% out-of-sample 验证
- **多市场分开**：BTC 家族 / 主流山寨 / memecoin 分别调优
- **手续费假设保守**：VIP 0 + BNB 抵扣，避免 overfitting 到低费用

---

## 15. 上线路线（相比 v1 更长）

### Phase 0：现货冒烟测试（1-2 天）
同 v1 §15 Phase 0。

### Phase 1：数据管道 + 深度回测（3-4 周）
- FundingHistoryTracker
- 全市场可用交集刷新
- 6-12 个月历史数据回测
- 参数扫描 + train/test 验证
- **验收标准**：out-of-sample 年化 ≥ 8%、最大回撤 ≤ 15%

### Phase 2：Paper 长周期（4-6 周）
- 完整 PairExecutor + ExitTriggerEngine
- 全市场扫描 + regime 判定
- BasisMonitor + RiskGuardian
- Paper 跑 4-6 周（覆盖至少 3-4 个 regime 变化）
- **验收标准**：Paper 结果与回测偏差 < 30%

### Phase 3：LIVE 小仓验证（4-6 周）
- 单笔 100-200 USD × 3 对
- 每天人工 review event log
- 关注：saga 成功率、真实 fee、真实 slippage、basis 分布
- **验收标准**：真实年化 ≥ 6%（低于回测但仍正）

### Phase 4：全市场放量（持续）
- 分批放大 max_open_pairs 从 3 → 10 → 20
- notional 从 200 → 1000 → 5000
- 定期回顾 vs 预期

**总时长**：约 3-4 个月完整验证周期，远长于 v1 估算的 5-7 周。**这是策略本质决定的**（regime 转换周期本身就是几周到几月）。

---

## 16. 决策点回顾（v2 版）

以下参数用户需明确配置：

| 决策 | 保守 | 平衡（推荐） | 激进 |
|---|---|---|---|
| Regime lookback 次数 | 12 (~96h) | **6 (~48h)** | 3 (~24h) |
| Min avg funding (8h eq) | 0.06% | **0.04%** | 0.02% |
| Min hit ratio | 5/6 (83%) | **4/6 (67%)** | 3/6 (50%) |
| Exit regime break count | 3 | **2** | 1 |
| Exit basis delta bps | 40 | **60** | 100 |
| Take profit % | 0.5% | **0.8%** | 1.5% |
| Max hold hours | 72 (3d) | **168 (7d)** | 336 (14d) |
| Perp leverage | 1x | **2x** | 3x |
| Max open pairs | 3 | **8** | 20 |
| Symbols scope | 白名单 top 10 | **全市场 top 100** | 全市场 |

---

## 17. 收益预期（重新校准）

**v1 错误估算**："15-30% 年化"，基于快进快出无法达成。

**v2 合理估算**：

| 场景 | 年化 |
|---|---|
| 只做 BTC/ETH 大盘 | 2-6% |
| BTC+ETH+主流山寨 top 30 | 8-15% |
| 全市场山寨精选（中低频） | 15-30% |
| 山寨激进 + 高杠杆 | 30-60%（爆仓风险大） |
| 极端 memecoin 事件驱动 | 单次可达 50%+/月，但 sizing 极小 |

参照：Cumberland、GSR 等专业做市商的公开数据，机构级 funding arb 年化约 8-15%。散户配置目标 10-20% 是合理的。

---

## 18. 已知限制

- **V1 不做**：
  - **负 funding 场景**（见 §0.2b，需 margin 借币做空 spot 或跨合约对冲，V2.1 不实现）
  - 现货杠杆借贷（提升资金效率）
  - 跨交易所（Binance ↔ OKX/Bybit）
  - 跨合约对冲（USDT-M vs USDC-M，可覆盖负 funding，作为 v3 优先扩展）
  - 三腿基差（perp + 期货 + 现货）
- **V2 可扩展**：多交易所、margin 借贷、ML 预测 funding regime、组合优化

---

## 19. 与用户的核心承诺

- **免疫方向**：涨跌不影响本金（basis 稳定假设下）
- **中低频**：一天 0-3 次入场，非高频交易
- **透明化**：每 pair 完整 P&L 归因（funding / basis / fee 三段）
- **多重触发出场**：不锁死一种止损，5 种触发保护
- **PAPER 长周期验证**：至少 4-6 周确认 regime 判定准确
- **收益预期务实**：8-20% 年化（保守到平衡配置），不是暴利策略

---

**文档版本**：v2.0（Funding Regime Trading）
**关键决策**：从"结算捕捉"改为"regime 趋势"，手续费一次成本、多次结算摊销
**建议阅读顺序**：0（思想）→ 4（regime）→ 6-8（入场/持仓/出场）→ 14（回测）→ 15（上线）→ 17（收益预期）
