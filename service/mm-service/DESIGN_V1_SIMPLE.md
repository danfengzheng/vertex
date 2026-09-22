# mm-service V1 简化版 · 币安风格网格

> 目标：**先复刻币安网格的核心行为**（区间 + 步长 + 反手），加上**增量挂单**（不一次性挂满）、**等差/等比**双模式、**预览面板**。
> 复杂功能（方法 α、5 层风控、梯队平仓、自适应步长等）留给 V2/V3（见 DESIGN_V2.md）。

---

## 0. 与 V2 的关系

| 维度 | V1（本文） | V2（DESIGN_V2.md） |
|---|---|---|
| 定位 | 生产可用 MVP | 高阶自适应做市 |
| 方向 | 单侧（LONG_ONLY / SHORT_ONLY / BOTH）| 双侧对称 |
| 步长 | 固定（等差/等比预生成）| 自适应 α |
| 挂单 | 滑动窗口增量挂单 | 滑动窗口 + 方法 α CLOSE 单 |
| 风控 | 单侧账户级 pnl + 手动停机 | 5 层保护 + Depletion |
| 平仓 | 反手挂单 + 手动止盈 | 梯队平仓 + EMA_TRAIL |
| 预览 | ✅ 本次核心 | — |
| 上线目标 | 1 周内 | 未定 |

V1 落地后，**V2 视为 V1 的可选升级层**，字段以"新增可选列 + 默认关"方式加，不破坏 V1。

---

## 1. 核心概念

### 1.1 单策略 = 单方向

**方案 B**：拆分设计，一个策略只做一个方向（或 BOTH 快捷模式）。

```
side ∈ {LONG_ONLY, SHORT_ONLY, BOTH}
```

约束（Service 层校验）：

- 同 `(exchange, symbol, account_id)` 已存在 `BOTH` → 拒绝再建任何新的
- 同 `(exchange, symbol, account_id)` 已存在 `LONG_ONLY` → 只允许再建 `SHORT_ONLY`
- 反之亦然
- 需要"多空异构分布"的用户 → 分别建 `LONG_ONLY` + `SHORT_ONLY`，每个策略独立配置 mode / lower / upper / count / qty

依赖 Binance **Hedge Mode**（双向持仓模式）。启动时若账户是 One-way，Service 拒绝启动并提示。

### 1.2 价格分布模式

```
mode ∈ {ARITHMETIC, GEOMETRIC}
```

#### 等差 Arithmetic
```
step = (upper - lower) / grid_count
price[i] = lower + i × step,  i ∈ [0, grid_count]
```
每格价差 = 固定金额；低价区利润率高、高价区利润率低。

#### 等比 Geometric
```
ratio = (upper / lower) ^ (1 / grid_count)
price[i] = lower × ratio^i,  i ∈ [0, grid_count]
```
每格价差 = 固定百分比（`(ratio-1) × 100%`）；全区间利润率恒定。

**输出**：格子价格数组 `prices[]`（长度 = `grid_count + 1`），从低到高排列。

### 1.3 滑动窗口挂单

不一次性挂满所有格子，只在 `currentPrice` 附近上下各 `window_size` 格挂单。

```
上方最多挂 window_size 个 SELL（价位来自 prices[] 中 > currentPrice 的最近 N 格）
下方最多挂 window_size 个 BUY  （价位来自 prices[] 中 < currentPrice 的最近 N 格）
```

- 价格移动 → 补挂前进方向的新格 + 撤销远端超出窗口的格
- 边界外（<lower 或 >upper）不挂
- side 限制：`LONG_ONLY` 只挂 BUY（下方）+ 成交后上方挂 SELL 平仓；`SHORT_ONLY` 对称

### 1.4 反手挂单 + 网格占用规则 ⭐（V1 核心）

**核心不变量**：**每个 gridIndex 在同一 positionSide 上，同一时刻最多只有一个"开仓头寸"未平**。开仓成交后，直到对应的平仓单成交前，**滑动窗口不得在原 gridIndex 补挂新的开仓单**。

#### 状态机

每个 gridIndex（× positionSide）有三种状态：

| 状态 | 描述 | 滑动窗口行为 |
|---|---|---|
| **EMPTY** | 没有活单，也没有未平仓头寸 | 允许挂开仓单 |
| **OPEN_ORDER_LIVE** | 挂着未成交的开仓单 | 视为已挂，不重复挂 |
| **POSITION_HELD** | 开仓已成交、对应平仓单在其他格挂着（还没成交）| **禁止再挂开仓单** |

状态转移：

```
EMPTY
  ├─ 挂开仓单 (LIMIT BUY/SELL, role=OPEN) → OPEN_ORDER_LIVE
  └─（无其他入口）

OPEN_ORDER_LIVE
  ├─ 开仓单成交（FILLED）→ 挂对手方向的 CLOSE 单 → POSITION_HELD
  └─ 撤单（窗口滑出）→ EMPTY

POSITION_HELD
  ├─ 平仓单成交（FILLED）→ EMPTY（此时也释放平仓单所在的 gridIndex）
  ├─ 平仓单撤单（异常）→ 保持 POSITION_HELD，重挂 CLOSE 单
  └─（无其他出口）
```

#### 反手挂单逻辑

```
onFill(order):
    if (order.role == OPEN):
        // 开仓成交 → 在对手方向下一格挂 CLOSE 平仓
        closeSide  = opposite(order.side)     // BUY→SELL, SELL→BUY
        closeIdx   = (order.side == BUY) ? order.gridIndex + 1 : order.gridIndex - 1
        closePrice = prices[closeIdx]

        placeOrder(
            side=closeSide,
            positionSide=order.positionSide,   // LONG 侧头寸的 CLOSE 走 LONG positionSide
            role=CLOSE,
            gridIndex=closeIdx,
            price=closePrice,
            pairOrderId=order.id
        )

        // 关键：origin gridIndex 从 OPEN_ORDER_LIVE → POSITION_HELD
        state.markPositionHeld(order.gridIndex, order.positionSide)

    else if (order.role == CLOSE):
        // 平仓成交 → 释放两个 gridIndex
        state.markEmpty(order.gridIndex, order.positionSide)                    // 平仓单所在格
        state.markEmpty(pairOf(order).gridIndex, order.positionSide)            // 原开仓格
        // 现在滑动窗口可以在这两格重新补挂
```

#### 滑动窗口 diff 时的过滤

```
reconcileWindow(currentPrice):
    // 期望窗口内应挂的开仓单集合
    expectedOpenIdxs = computeWindowIndexes(currentPrice, side, window_size)

    for idx in expectedOpenIdxs:
        if state.slotState(idx) == EMPTY:
            placeOpenOrder(idx)              // 只有真空才挂
        // 其他状态跳过：LIVE 已挂无需重复；HELD 等平仓再释放

    for order in state.liveOpenOrders():
        if order.gridIndex not in expectedOpenIdxs:
            cancelOrder(order)               // 窗口滑走，撤销远端
```

**注意**：只撤销 `role=OPEN` 且未成交的单；`role=CLOSE` 的单不论距离多远都必须留着（否则位置永远无法平）。

#### `LONG_ONLY` / `SHORT_ONLY` 的方向限制

- `LONG_ONLY`：
  - 只允许在 `currentPrice` 下方的 gridIndex 挂 `role=OPEN, side=BUY, positionSide=LONG`
  - 只允许 CLOSE 单是 `side=SELL, positionSide=LONG`（reduceOnly=true）
  - 不主动开 SHORT
- `SHORT_ONLY`：对称
- `BOTH`：两侧都开（LONG 头寸走 LONG positionSide，SHORT 头寸走 SHORT positionSide），依赖 Hedge Mode 隔离

---

## 2. 数据模型

### 2.1 grid_strategy 新增字段

在现有 `grid_strategy` 表基础上追加：

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `side` | `ENUM('LONG_ONLY','SHORT_ONLY','BOTH')` | `BOTH` | 交易方向 |
| `grid_mode` | `ENUM('ARITHMETIC','GEOMETRIC')` | `ARITHMETIC` | 价格分布模式 |
| `lower_price` | `DECIMAL(20,8)` | NULL | 区间下限 |
| `upper_price` | `DECIMAL(20,8)` | NULL | 区间上限 |
| `grid_count` | `INT` | NULL | 网格数量（价位数 = count + 1） |
| `qty_per_grid` | `DECIMAL(20,8)` | NULL | 每格数量（复用现有 `qty_per_grid`，若字段名相同则直接沿用） |
| `window_size_per_side` | `INT` | 10 | 单侧滑动窗口大小（复用现有字段） |

注：`center_price` / `step_pct` 在 V1 里作为**运行时快照**保留（滑动窗口计算时写入），不作为主配置字段。

### 2.2 side ↔ longSideEnabled/shortSideEnabled 映射

`side`（V1 简写）与已有 `long_side_enabled` / `short_side_enabled`（V2 细粒度）的关系由 Service 层写入时同步（写 side 时覆盖两个 enabled 位）：

| side | long_side_enabled | short_side_enabled |
|---|---|---|
| `LONG_ONLY` | 1 | 0 |
| `SHORT_ONLY` | 0 | 1 |
| `BOTH` | 1 | 1 |

读取时以 `side` 为主，enabled 位为冗余快照（也用于 V2 分侧减仓/停机逻辑判断）。

### 2.3 grid_order.role 使用

`grid_order.role` 字段已存在 (`OPEN / CLOSE / LADDER_CLOSE`)，V1 使用 `OPEN` 和 `CLOSE`：

- `OPEN`：滑动窗口挂的开仓单，`positionSide` 匹配 `side`（LONG 头寸挂 BUY OPEN；SHORT 头寸挂 SELL OPEN）
- `CLOSE`：反手挂单，`reduceOnly=true`，`pairOrderId` 指向对应的 OPEN 单
- `LADDER_CLOSE`：V1 不使用（V2 梯队平仓才用）

### 2.4 GridSlot 扩展（内存态）

现有 `GridSlot` 只跟踪 `longSideOrderId / shortSideOrderId`。V1 需要在 `GridState` 内新增一个 map：

```java
// key: gridIndex, value: 该 index 上的 LONG / SHORT 各自"是否有 POSITION_HELD"
ConcurrentHashMap<Integer, EnumSet<PositionSide>> heldPositions;
```

或者更结构化地把 GridSlot 扩为：

```java
enum SlotState { EMPTY, OPEN_ORDER_LIVE, POSITION_HELD }
Map<PositionSide, SlotState> slotStates;
```

Service 层在 `onFill` 时更新，在滑动窗口 `reconcile` 时读取。

### 2.5 步长字段：V1 自动推导 baseStepPct

`base_step_pct` 是 V2 引擎依赖的步长字段。V1 中步长完全由 `(lower, upper, gridCount, gridMode)` 唯一确定，因此 V1 **不让用户填 baseStepPct**，而是在 `GridStrategyService.applyDefaults` 与 `update` 里自动反推：

- `GEOMETRIC`: `baseStepPct = ((upper/lower)^(1/count) - 1) × 100`
- `ARITHMETIC`: `baseStepPct = (upper - lower) / count / midPrice × 100`（用中位价做 % 近似）

前端表单里 `baseStepPct` 字段 **disabled**，仅作为回显。

### 2.6 挂单数量：windowSizePerSide 直接控制

**挂单数量 = `windowSizePerSide` × 2**（LONG 侧 N + SHORT 侧 N；LONG_ONLY / SHORT_ONLY 只有 N）。

由于步长已由几何配置确定，windowSize 与步长的组合直接决定 **单侧覆盖率**：
```
单侧覆盖% = stepPct × windowSizePerSide
单侧覆盖价 = stepAbsolute × windowSizePerSide
```

预览 API 返回 `windowCoverage` 区块，帮助用户直观判断：
- 覆盖 < 1% → 价格急变时容易滑出窗口
- 覆盖 > 10% → 同时挂单多，rate limit 压力大

### 2.7 浮动网格（V1 核心模型）

**V1 使用浮动网格**：slot 价不是从静态 `prices[]` 数组取离散值，而是以**启动时的 currentPrice 为 anchor**、按 step 相对偏移动态计算。

- 启动 anchor: `centerPrice = currentPrice`（BookTicker 实时价）
- BUY 第 i 档: `centerPrice - i × step`
- SELL 第 i 档: `centerPrice + i × step`
- 反手 CLOSE 价: `gridPrice + step`（LONG 成交后往上一格 SELL）或 `gridPrice - step`（SHORT 成交后往下一格 BUY）
- CLOSE 成交后释放原 OPEN 格: `gridPrice - step`（LONG）/ `gridPrice + step`（SHORT）

**step 的两种精度实现**（`SlidingWindowManager.computeSlotPriceRaw`）：

| 模式 | step 计算 | 公式 |
|---|---|---|
| ARITHMETIC | `absStep = (upper - lower) / count` 精确 BigDecimal | `anchor + offset × absStep` |
| GEOMETRIC | `stepFrac = stepPct / 100` | `anchor × (1 + stepFrac × ｜offset｜)`（+ 或 ÷ 按方向）|

`absStep` 存在 `GridState.absoluteStep`，`ARITHMETIC` 时非 null。`GEOMETRIC` / V2 fallback 时 null，走乘除逼近路径。

**关键性质**：

1. **首档紧贴当前价**：BUY[1] = center − step（如 2408.16 − 4 = 2404.16），gap 严格等于 step，不像固定网格会浪费 0~step 的 buffer
2. **grid 边界仍生效**：`strategy.getLowerPrice()` / `getUpperPrice()` 作为 hard bound，超出的偏移直接跳过（不挂）
3. **精度**：
   - ARITHMETIC 用 BigDecimal 加减法，**零累积误差**
   - GEOMETRIC 每步只做一次 multiply / divide，double 精度足够，且 `roundPrice` 每次 snap 到 tick
   - 反手价用 `gridPrice`（挂单价）作 anchor，不用 `fillPrice`（滑点污染）
4. **shift 时 grid 平移**：`shiftWindow(newCenter)` 用 newCenter 作为新 anchor，所有 slot 价重新计算并对齐。

**V2 兼容**：`state.gridMode == null` 且 `absoluteStep == null` 时，`computeSlotPriceRaw` 走 GEOMETRIC 逼近，等价于老 V2 stepPct 逻辑。

### 2.8 并发防御与追踪完整性

**a) `refillEmptySlots` per-strategy 锁 + sentinel 预占位**

同策略两次 CLOSE 在毫秒内相继成交时，串行化 refill 逻辑，避免看到"刚生成 PLACE 意图但 orderId 未回填"的 slot 时误判为 EMPTY 二次挂单：

```
Thread A (CLOSE 1): lock → 看到 slot EMPTY → 生成 PLACE + slot.orderId = -1 (sentinel) → unlock
Thread B (CLOSE 2): lock → 看到 slot.orderId != null → 视为 OPEN_ORDER_LIVE 跳过 → unlock
Executor 异步: 拿到真实 orderId → recordOrderPlaced 覆盖 sentinel
```

若 PLACE 失败（币安拒绝 GTX、其他错误码、整批异常），`OrderIntentExecutor` 在三个失败分支都显式调 `SlidingWindowManager.clearPendingSentinel(strategyId, gridPrice, positionSide)` 复位 slot 供下轮重试。

**b) `recordOrderPlaced` 懒创建 slot**

CLOSE 单目标价（`prices[gridIdx ± 1]`）在 OPEN 落于窗口最边缘时可能超出初始 `state.slots` 覆盖。为了追踪不丢失，`recordOrderPlaced` 找不到 slot 时懒创建：

- V1 模式：`gridIdx = findClosestIdx(prices, gridPrice) - centerAbsIdx`（保留中枢相对下标语义）
- V2 模式：`gridIdx = 0` 兜底
- 按 gridPrice 与 centerPrice 大小归到 `upperSlots` 或 `lowerSlots`

这样即使 CLOSE 挂在窗口外，其 `orderId` 也能被追踪，CLOSE 成交后的 slot 释放不会静默失败。

**c) `removeState` 同步清 `refillLocks`**：防止长期运行下 lock map 泄漏。

### 2.9 V2 兼容

V2 的自适应步长、α 方法、5 层风控字段全部保留，仅在 V1 里默认关闭（`adaptive_step_enabled=0` 等）。

---

## 3. 组件与流程

### 3.1 GridPriceCalculator（工具类）

**核心 API**：

```java
public class GridPriceCalculator {
    /** 生成所有格子价格（升序）*/
    public static BigDecimal[] calculate(
        BigDecimal lower, BigDecimal upper, int count, GridMode mode);

    /** 等比模式下每格恒定利润率（%）*/
    public static double geometricStepPct(
        BigDecimal lower, BigDecimal upper, int count);

    /** 等差模式下平均利润率（%）— 高价区偏低，此为均值 */
    public static double arithmeticAvgStepPct(BigDecimal[] prices);

    /** 找到 currentPrice 在 prices[] 中的最近下标 */
    public static int findClosestIdx(BigDecimal[] prices, BigDecimal target);
}
```

### 3.2 GridPreviewService

**入参** (`GridPreviewRequest`):
```
symbol, side, mode, lower, upper, gridCount,
qtyPerGrid, currentPrice, leverage, feeRate (可选，默认 0.0005)
```

**返回** (`GridPreviewVO`)：见 §4。

**计算**：
1. `GridPriceCalculator.calculate(...)` → `prices[]`
2. `findClosestIdx(prices, currentPrice)` → `currentIdx`
3. 根据 side 确定 buySlots / sellSlots
4. 累加最大多仓 / 空仓的数量、成本、均价
5. 保证金 = `maxNotional / leverage`
6. 强平价（隔离仓简化）：
   - 多仓 `≈ avgPrice × (1 - 1/leverage + 维持保证金率)`
   - 空仓 `≈ avgPrice × (1 + 1/leverage - 维持保证金率)`
   - 维持保证金率简化取 0.005（0.5%），未来接入 Binance leverageBracket 精算
7. 单轮利润率 = 步长% - 2 × feeRate × 100
8. Warnings 列表（详见 §5）

### 3.3 GridEngine 单策略主循环（V1）

```
onStart(strategy):
    prices = GridPriceCalculator.calculate(lower, upper, count, mode)
    state = new GridState(prices, side)
    // 全格初始 SlotState=EMPTY；从 DB 恢复 role=OPEN 的挂单 → LIVE；有未平 CLOSE 单的原格 → HELD
    recoveryFromDB(state)
    subscribeBookTicker()
    scheduleReconcile()   // 每 5s

onBookTicker(newP):
    reconcileWindow(newP)         // §1.4 滑动窗口 diff（跳过 HELD 与 LIVE）

onFill(order):
    if order.role == OPEN:
        placeCloseOrder(order)    // 反手挂 CLOSE
        state.setSlot(order.gridIndex, order.positionSide, POSITION_HELD)
    elif order.role == CLOSE:
        state.setSlot(order.gridIndex, order.positionSide, EMPTY)                // 平仓格释放
        state.setSlot(pairOf(order).gridIndex, order.positionSide, EMPTY)        // 原开仓格释放
        recordPnl(order)          // 净利润 = (close - open) × qty × sign - fees
    updatePosition(order)
    checkAccountRisk()            // V1 只做账户级 pnl 阈值 + 手动停机

onScheduledReconcile(每 5s):
    diffLocalVsExchange()         // 对账：交易所实际单 vs 本地状态
    resendLostOrders()            // 本地有 LIVE 但交易所无 → 重挂
    // 注意：不要撤销 role=CLOSE 的远端单（可能远离当前窗口）
```

**关键实现要点**：

1. `reconcileWindow` 只处理 `role=OPEN`，`role=CLOSE` 一律不动
2. `state.slotState(idx, positionSide)` 是所有决策的唯一真相源
3. 崩溃恢复：从 `grid_order` 表反推每个 slot 的状态
   - 有 `status=LIVE, role=OPEN` → OPEN_ORDER_LIVE
   - 有 `status=LIVE, role=CLOSE` → 该 CLOSE 单对应的 pairOrder.gridIndex 标为 POSITION_HELD
4. 挂 CLOSE 单时必须 `reduceOnly=true`，避免累积名义超过预期

**V1 简化点**：

- **无自适应步长**：`stepPct` 由 `prices[]` 静态决定
- **无方法 α CLOSE 单**：BUY 成交只挂 SELL（用 prices 上一格），不再挂"更远的 CLOSE 保护单"
- **无梯队平仓**：反手挂单即为平仓机制
- **风控只保留**：
  - `account_emergency_pnl_pct` 账户级触发
  - `long_stop_pnl_pct` / `short_stop_pnl_pct` 单侧触发
  - 手动 `/stop` / `/emergency`
- **5 层保护 / Depletion / TrendScore 全部关闭**

---

## 4. 预览面板返回结构

### 4.1 `GridPreviewVO`

```
{
  "basics": {
    "symbol": "ETHUSDT",
    "side": "BOTH",
    "mode": "GEOMETRIC",
    "lowerPrice": 1800,
    "upperPrice": 2400,
    "gridCount": 100,
    "qtyPerGrid": 0.05,
    "currentPrice": 2100,
    "leverage": 3,
    "feeRate": 0.0005
  },
  "distribution": {
    "stepPricesFirstFew": [1800.00, 1805.19, 1810.39, ...],   // 前 5 格价格用于前端图
    "stepPricesLastFew":  [..., 2395.20, 2400.00],            // 后 5 格
    "avgStepAbsolute": 6.00,                                  // 等差绝对值 / 等比均值
    "stepPct": 0.288,                                         // 等比：恒定；等差：均值
    "netProfitPerGridPct": 0.188                              // 扣双向手续费
  },
  "long": {
    "slots": 50,           // BUY 槽位数（currentPrice 下方格数，受 side 限制）
    "maxQty": 2.5,
    "maxNotional": 4875.0,
    "avgPrice": 1950.0,
    "requiredMargin": 1625.0,
    "estLiquidationPrice": 1300.0
  },
  "short": {
    "slots": 50,
    "maxQty": 2.5,
    "maxNotional": 5625.0,
    "avgPrice": 2250.0,
    "requiredMargin": 1875.0,
    "estLiquidationPrice": 3000.0
  },
  "totals": {
    "totalRequiredMargin": 3500.0,
    "maxAggregateNotional": 10500.0
  },
  "roundProfit": {
    "grossPct": 0.288,
    "netPct": 0.188,
    "netUsdtPerRound": 0.79
  },
  "warnings": [
    "空头强平价 3000 已高于区间上限，接近区间上沿时高风险"
  ]
}
```

### 4.2 前端渲染

在 `GridStrategyForm.tsx` 表单右侧或下方增加"预览"面板；用户改动任一预览相关参数后 **debounce 300ms** 调用 `/admin/mm/grid/preview` 拿最新 VO 并重绘。

面板分区展示：**基础 → 价格分布 → 多头满仓 → 空头满仓 → 保证金合计 → 单轮利润 → 警告**。

---

## 5. Warnings 规则

| 触发条件 | 文案 |
|---|---|
| `upper <= lower` | "区间无效：上限必须大于下限" |
| `gridCount < 2` | "格数过小，至少 2" |
| `gridCount > 500` | "格数 > 500，rate limit 压力大" |
| `netProfitPerGridPct < 0.05` | "扣费后每格净利润 < 0.05%，接近盈亏平衡" |
| `qtyPerGrid × lower < 5` | "单格名义 < 5 USDT，可能低于交易所最小下单" |
| `totalMargin > 账户余额`（可选，若前端能拿到）| "预估保证金超过账户可用余额" |
| `estLiquidationLong > lower × 0.9` | "多仓强平价接近区间下限，跌破网格底端将强平" |
| `estLiquidationShort < upper × 1.1` | "空仓强平价接近区间上限，涨破网格顶端将强平" |
| `side==BOTH && !hedgeMode` | "双向模式需 Hedge Mode，请在币安设为双向持仓" |

---

## 6. 接口清单（新增）

| Method | Path | 说明 |
|---|---|---|
| `POST` | `/admin/mm/grid/preview` | 预览：不落库、纯计算 |

**Body**：`GridPreviewRequest`
**Response**：`Result<GridPreviewVO>`

其他 CRUD / start / stop 沿用现有 `GridStrategyController`。Service 层需增加：

- `GridStrategyService.create()` 里加 **side 冲突校验**
- `GridStrategyService.start()` 里加 **Hedge Mode 检查**（BOTH 时必须）

---

## 7. 前端改造清单

`vertex-ui/src/pages/mm/GridStrategyForm.tsx`：

1. 表单顶部加 `side` 选择：`只做多 / 只做空 / 双向`
2. 加 `gridMode` 单选：`等差 / 等比`
3. 加 `lowerPrice / upperPrice / gridCount / qtyPerGrid`（若原有则复用）
4. 右侧插入 `<GridPreviewPanel>` 组件
5. 表单参数变化 → debounce 300ms → 调用 `/admin/mm/grid/preview` → 更新面板
6. 保存前提示：Warnings 高危项（红色）需用户勾选"我知道风险"才能提交

`GridPreviewPanel.tsx`（新组件）：结构见 §4.1。

---

## 8. 开发拆解 & 估时

| # | 任务 | 估时 |
|---|---|---|
| 1 | SQL V37：加字段 + 索引 | 30min |
| 2 | Entity/DTO 加字段 | 30min |
| 3 | `GridPriceCalculator` + 单测 | 1h |
| 4 | `GridPreviewService` + `GridPreviewVO/Request` | 2h |
| 5 | Controller `/preview` 端点 | 30min |
| 6 | Service：side 冲突 + HedgeMode 校验 | 1h |
| 7 | GridEngine：改用 `prices[]` 替代 `stepPct` 计算 | 2h |
| 7b | GridState / GridSlot：加 `SlotState` 三态机 + `slotStates` map | 2h |
| 7c | SlidingWindowManager：`reconcileWindow` 加 EMPTY 过滤 + 跳过 HELD/LIVE | 2h |
| 7d | onFill 逻辑：OPEN→挂 CLOSE + 标记 HELD；CLOSE→释放两格 + P&L 记录 | 2h |
| 7e | 崩溃恢复：从 `grid_order` 表反推 SlotState | 1h |
| 7f | Service 层：side ↔ longSideEnabled/shortSideEnabled 双向同步 + Hedge Mode 检查 | 1h |
| 8 | 前端表单加字段（side / mode / lower / upper / count / qty） | 2h |
| 9 | 前端 `GridPreviewPanel` 组件 | 3h |
| 10 | 前端集成 debounce 调用 + 保存前警告 | 1h |
| 11 | E2E 联调 + Paper 跑通 | 3h |
| **合计** | | **~24h（3 人日）** |

---

## 9. 上线 checklist

- [ ] V37 SQL 迁移到 dev DB 无报错
- [ ] 后端启动无 Bean 冲突
- [ ] 单测：`GridPriceCalculator` 等差 / 等比 / 边界（count=2、lower=upper）
- [ ] Preview API：手动 curl 3 组参数
- [ ] 前端表单能保存并回读
- [ ] 前端预览面板与后端返回字段一一对应
- [ ] Paper 模式：`side=LONG_ONLY, mode=GEOMETRIC` 跑通滑动窗口
- [ ] Paper 模式：`side=BOTH, mode=ARITHMETIC` 跑通反手挂单
- [ ] **不变量验证**：单元测试 `openThenNoReopen`
    - 步骤：placeOpen@i → fillOpen → 窗口滑动仍覆盖 i → 断言不重挂 open@i
    - 步骤：fillClose@i+1 → 断言 open@i 被重新挂
- [ ] **不变量验证**：同 gridIndex 同时最多 1 个 OPEN（DB unique 索引校验：`(strategy_id, grid_index, position_side, role=OPEN, status IN LIVE/PENDING)`）
- [ ] 崩溃恢复：kill 进程 → 重启 → DB 反推 SlotState 无遗漏（含 HELD）
- [ ] Live 起步 `qty_per_grid = 最小名义 × 2`、`window_size=3` 观察 24h
