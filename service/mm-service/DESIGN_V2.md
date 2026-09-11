# mm-service V2 设计文档（目标模型）

> 版本：2026-09-11
> 状态：**只是蓝图，不是当前实现**。当前实现见 `DESIGN.md`（V1）。
> 目标：向币安网格模型对齐 + 保留 3 个差异化增强 + 硬风控

---

## 一、设计目标

**用一句话描述 V2**：
> **币安 Neutral Grid 语义 + 打分驱动的动态步长 + 硬边界/仓位/PNL 三重止损**

**核心变化 vs V1**：

| 维度 | V1 现状 | V2 目标 |
|---|---|---|
| 网格边界 | 无（Adaptive 追踪 EMA20） | **upper/lower price 硬边界**（价格穿越 = 停加仓）|
| Grid Update 语义 | fill 后 rearm 同价 → 可累积无限次 | **fill 后该挡 switched off，反向单接管**（币安式）|
| 步长调整 | AdaptiveStepCalculator 换绝对值 + 全窗口 shift | **step = base × (1 + X)**，X 由趋势打分驱动，默认 REPORT_ONLY 只上报（见 §16 三条生效路径）|
| 止损 | 5 层 Layer + 1 个账户级 pnl（opt-in）| **3 种 Stop Trigger**（Price/PNL/ROI）+ 单侧仓位上限 |
| 保护动作 | 5 层（NORMAL/L1/L2/L3/L4）+ 梯队 + emergency | **2 个动作**：停加仓 or 平仓 |
| 挂单方式 | 一次挂满 windowSize × 2 | 密网格支持**分批挂单**（Dynamic Order Mechanism）|
| 方向 | 只做 Neutral | Long / Short / Neutral 三种 |
| 计价 | qty × window 反推 | **投入金额 (investment)** 直接算 |
| 差异化 | Depletion 快反应 + SmartPricer + Adaptive step | **全部保留**（作为币安基础上的增强层）|

---

## 二、借鉴自币安的核心机制

### 2.1 价格边界（Upper/Lower Price）

用户显式配 `upper_price` / `lower_price`。价格越界即停加仓（**天然位置止损**）。

```
if mark_price > upper_price:  停挂 BUY 侧新单（SHORT 侧继续）
if mark_price < lower_price:  停挂 SELL 侧新单（LONG 侧继续）
if 双侧都越界（不可能）:      整体停策略
```

**Neutral 情况**：
- 价格在 `[lower, upper]` 内 → 双侧正常
- 价格出区间 → 该侧不再挂新的加仓方向 slot（BUY 侧或 SELL 侧）；已持仓的 slot 通过相邻 slot 翻转正常平仓

### 2.2 网格模式（Arithmetic / Geometric）

**Arithmetic（等差）**：
```
price_diff = (upper - lower) / grid_count
price_i = lower + price_diff × i
```

**Geometric（等比）**：
```
ratio = (upper / lower) ^ (1 / grid_count)
price_i = lower × ratio ^ i
```

用户 UI 二选一。`profit_per_grid` 由公式计算并展示（扣手续费后的每格净利润）。

### 2.3 Grid Update 语义（核心，与 V1 完全不同）

**V2 明确采用币安模型（方案 α）**：
- **没有独立的 CLOSE 订单概念**。所有单都是 grid slot 拓扑的一部分。
- 每档 slot 有 3 种状态：`BUY` / `SELL` / `OFF`。
- 成交 = slot 状态翻转 + 邻格补挂。

**术语约定**（后文所有章节统一）：
- `slot_i` 表示 grid_index=i 的挡位（0 ≤ i < grid_count）
- `slot_i.price` 是该挡价格
- `k_off` 表示当前 switched off 的中枢档索引

**初始状态**：
```
grid_count = N，各挡价格 P_0, P_1, ..., P_{N-1}
mark 落在 slot_k 附近，则 k_off = k

Neutral:
  slot_0..slot_{k-1}     status = BUY   （mark 下方所有挂 BUY）
  slot_k                 status = OFF   （中枢，暂不挂）
  slot_{k+1}..slot_{N-1} status = SELL  （mark 上方所有挂 SELL）

Long only:
  slot_0..slot_{k-1}     status = BUY
  其他所有                status = OFF
  （mark 涨时 BUY fill → 上方转 SELL 平出）

Short only:
  slot_{k+1}..slot_{N-1} status = SELL
  其他所有                status = OFF
```

**成交后的状态翻转**（Neutral 举例）：
```
slot_j (原 BUY) fill:
  → slot_j.status = OFF        （原 BUY 挡"消耗掉"）
  → slot_{j+1} 若原为 OFF → 变 SELL 挂上（上一格接管为止盈）
  → k_off 更新为 j
  → mark 若继续下跌，slot_{j-1} 会被 fill

slot_j (原 SELL) fill:
  → slot_j.status = OFF
  → slot_{j-1} 若原为 OFF → 变 BUY 挂上（下一格接管为止盈）
  → k_off 更新为 j
  → mark 若继续上涨，slot_{j+1} 会被 fill
```

**每次 slot 状态翻转 = 一次可能的 arb round-trip**：
- 市场先跌：slot_j BUY fill @P_j → slot_{j+1} 变 SELL 挂上
- 市场后涨：slot_{j+1} SELL fill @P_{j+1} → slot_j 变 BUY 挂回
- 一个完整震荡 = 赚 `(P_{j+1} - P_j) × qty` 差价

**V1 的错误做法**（对比）：
```
BUY @P_j fill:
  → 独立派生 CLOSE SELL @(P_j × (1+step)) 挂上
  → 同价 BUY @P_j rearm     ← ❌ 允许重复累积
  → 若市场再跌到 P_j → 又 fill → 又 rearm → 无限累积
```

**V2 vs V1 差异关键点**：

| 维度 | V1 | V2 |
|---|---|---|
| 每档最多有几个 order | ∞（rearm 无限次）| 至多 1 个（BUY / SELL / OFF 三选一）|
| CLOSE 单 | 独立派生，价格 = fill × (1+step) | **没有独立 CLOSE**，就是相邻 slot 的 SELL/BUY |
| 单侧最大仓位 | 无理论上限 | **hard cap = grid_count × qty_per_grid** |
| P&L 归因 | OrderPairMatcher FIFO 配对 CLOSE 单 | 通过 slot 状态翻转 + weighted-avg-cost（见 §15）|
| slot_i.currentOrderId | N/A（每档一个 map）| 1 个字段 |

### 2.4 Stop Trigger（3 种，明确）

| 类型 | 触发条件 | 动作可选 |
|---|---|---|
| **Price** | mark price 达到 TP/SL 价 | 保仓 / 平仓 |
| **PNL** | 累计盈亏 (实现+浮动) 达到 X USDC | 保仓 / 平仓 |
| **ROI%** | 收益率达到 X% | 保仓 / 平仓 |

3 种可组合任选。触发时都会终止 grid（撤所有挂单）。是否平仓由用户配置决定。

**与 upper/lower_price 硬边界的区别与优先级**：

| 概念 | 字段 | 触发条件 | 动作 | 严重程度 |
|---|---|---|---|---|
| 硬边界 | `upper_price` / `lower_price` | mark 越界 | **只停加仓**（该方向不挂新 slot 单），已有仓位由相邻 slot 翻转正常平仓 | 软（策略仍 RUNNING）|
| Price Stop | `stop_price_upper` / `stop_price_lower` | mark 达到 | **终止策略**（撤所有单 + 可选平仓）| 硬（策略 → STOPPED）|

**触发优先级**：任一 Stop Trigger 命中即立即终止，不等硬边界处理。合理配置示例：
```
lower_price = 2400          ← 网格下界（跌破停加仓）
upper_price = 2600          ← 网格上界（涨破停加仓）
stop_price_lower = 2200     ← 硬止损（跌破直接平仓终止）
stop_price_upper = 2800     ← 硬止盈（涨破直接平仓终止）
```
硬边界 [2400,2600] 是"网格生效区"，Stop [2200,2800] 是"策略生死线"。留缓冲避免误触发。

### 2.5 Dynamic Order Mechanism（分批挂单）

**V2 采用显式配置**（详见 §14）：
- 配置字段 `active_window_size`（NULL=全挂，非空=只挂 mark 附近 ±N 挡）
- 币安官方是隐式规则（grid_count>169 自动启用 169 单窗口）
- V2 改为用户可控，任何 grid_count 都能强制 dynamic（多策略共享 200 挂单配额时有用）

具体伪代码、buffer、fill 驱动即时滑动等，见 §14。

### 2.6 Trailing Up/Down（可选，默认关）

**语义**：mark 突破 upper/lower 时，整个 grid 平移追随，避免"策略停在原区间空转"。

**Upper Trailing 触发流程（纯平移，保持网格形状不变）**：
```
Precondition: trailing_upper_enabled = 1
mark > upper_price:
  1. new_upper = mark × (1 + trailing_upper_offset_pct / 100)   # 默认 0.5% → 2600 × 1.005 = 2613
  2. new_lower = new_upper - (upper - lower)  ← 平移，保持区间宽度不变（绝对值差）
  3. 撤所有 status ≠ OFF 的 slot 挂单（batch cancel）
  4. 重建 grid_slot_v2 表（纯平移）：
       - 保留 grid_count / grid_mode / qty_per_grid 不变（step 也不变）
       - price 全部按新 lower/upper 重算
       - 找新 mark 对应的 k，其他槽按 direction 重置 status
       - **has_position 反推**（Long/Short 才需要，Neutral 保持 0）：
         a. 拉 positionRisk 得净持仓 net_qty 和加权 avg_cost
         b. n_positions = round(|net_qty| / qty_per_grid)
         c. 找 avg_cost 最接近的 slot j*（新拓扑）
         d. Long: 标记 slot j*..j*+n_positions-1 的 has_position=1（连续的下方 slot）
         e. Short: 标记 slot j*-n_positions+1..j* 的 has_position=1（连续的上方 slot）
         f. 若 n_positions × qty_per_grid ≠ |net_qty|（尾差），记 warning 日志
  5. 挂新 slot 单（按 §14 Dynamic Order 规则，只挂活跃窗口内的）
       - 此时 SlidingActiveWindow.computeDesiredSide 会用新 has_position 决定 Long/Short 平仓 slot 的挂单
  6. 更新 DB: strategy.lower_price = new_lower, upper_price = new_upper, last_shift_at = now(), trailing_shift_count += 1
  7. 发 GridShiftedEvent（P&L 归因不重置，只是网格拓扑变了）
```

Lower Trailing 对称。

**Trailing vs 手动 shiftWindow（§16.7）区分**：
- Trailing = 纯平移，**不用 X 重算 step**，grid_count 不变
- 手动 shiftWindow(recalc_step_with_x=true) = 用当前 X 重算 step 和 grid_count（走 §16 α1 路径）
- 两者互斥，Trailing 触发时不应该同时叠加 α1

**开销**：一次 Trailing 触发 = 撤 N 单 + 挂 N 单（N = 活跃窗口挡数）。启用后要接受这个成本。

**风险**：开启 Trailing 后**硬边界不再是止损**，趋势市场里网格会一路追涨追跌。适合 Neutral 且低杠杆 + 有 Stop Trigger 兜底的场景。**默认关闭**。

### 2.7 三种方向

| 方向 | 初始挂单 | 成交响应 | 说明 |
|---|---|---|---|
| **Long Grid** | mark 下方全部 BUY (slot_0..slot_{k-1})，其他 OFF | BUY fill 在 slot_j → 上方相邻 slot_{j+1} 转 SELL 平出 | 单边看涨；只做 LONG 方向 |
| **Short Grid** | mark 上方全部 SELL (slot_{k+1}..slot_{N-1})，其他 OFF | SELL fill 在 slot_j → 下方相邻 slot_{j-1} 转 BUY 平出 | 单边看跌；只做 SHORT 方向 |
| **Neutral** | mark 下方 BUY + mark 上方 SELL，slot_k OFF | 见 §2.3 | 双向震荡赚 arb |

**注意（更正）**：Long/Short 都**不是"上下全挂"**，而是**只在 mark 一侧挂**。这是币安语义。上一版文档描述有误已修正。

**初始开仓选项**（Long/Short 独有）：
- `open_initial_position = 0`（默认）：等 mark 自然到达 slot 才开仓
- `open_initial_position = 1`：策略启动时按当前市价一次性买满/卖满一侧仓位；数量 = `一侧挂单 slot 数 × qty_per_grid`（Long 时 slot 数 ≈ k = mark 所在 grid_index；Short 时 ≈ grid_count-k-1）

我们主要用 Neutral，但代码结构 GridBuilder 通过 direction 参数支持三种。

### 2.8 Investment 概念（金额驱动）

```
investment = 用户填的初始投入金额（USDC）
initial_margin = investment
notional_value = investment × leverage
per_grid_notional = notional_value / grid_count
per_grid_qty = per_grid_notional / avg_price（按 stepSize 对齐）
```

UI 只暴露 investment + leverage + grid_count + upper/lower，qty_per_grid **算出来**。用户不用做算术。

---

## 三、我们的差异化（V2 保留的"聪明"层）

在币安基础上加 3 个增强，不动核心 grid 逻辑：

### 3.1 打分驱动的动态步长（弱耦合）

**币安**：step 是固定的（用户配置 upper/lower/count，step 自动算出且不变）。

**V2**：允许一个可选的 `step_multiplier`，由趋势打分驱动：
```
X = trendScore(depletion, ADX, asymmetry, ...)   // 0.0 ~ maxX
effective_step = base_step × (1 + X)

如果用户不打开这个：X = 0，等同币安固定 step
如果打开：波动大时 X 变大 → step 展宽 → 新挂单更远 → 延缓累积
```

**关键（方案 α 语义，见 §16）**：`slot_i.price` 在 `GridBuilder.build()` 时一次算好、运行期不变，所以 X 天然不影响已存在的 slot 挂单。X 只有三条生效路径：
- **REPORT_ONLY（默认）**：只写监控指标，不影响挂单
- **SPARSE**：SlidingActiveWindowService 按 X 稀疏挂单（隔挡挂）
- **拓扑重建（α1）**：Trailing 触发或用户手动 shiftWindow 时用当前 X 重算 grid_count 重建 slot 表

### 3.2 SmartPricePlacer（保留，但有 slot 约束）

深度感知挂单：目标价薄簿位 → 跳到附近有真实排队的档。**这是 V1 的原创且币安没有**，实测有价值时保留。

**与方案 α slot 拓扑的兼容规则**：
- SmartPricePlacer 可以在 `slot_i.price ± tickSize × N`（默认 N=3）范围内微调实际挂单价，**但必须严格在 slot_i 和 slot_{i±1} 之间**（不跨越相邻 slot）
- 挂单价保存到 `grid_order_v2.grid_price`（实际挂单价），`slot_id` 关联仍指向逻辑 slot
- fill 事件回来通过 `clientOrderId → order.slot_id` 找 slot（不用 fill_price 反查）
- PositionAccountant 用 `fill_price`（实际成交价）算 avg_cost，不用 slot.price
- 若关闭 SmartPricePlacer (`smart_pricing_enabled=0`)：挂单价 = slot.price（严格对齐）
- 默认开启但受最大偏移 tickSize×3 约束（防止 SmartPricer 跨 slot）

### 3.3 Depletion 快反应（保留但降级）

**V1 用途**：给 5 层 Layer 打分。
**V2 用途**：仅作为 `X` 输入的一维信号（快过 ADX 反应）。**X 默认 REPORT_ONLY 只上报**，见 §16。

---

## 四、V2 风控设计（简）

**只有 2 个硬触发**：

### 4.1 单侧仓位上限
```
side_position_qty = 该侧已 fill 未平的净持仓 qty
                  = Σ (slot fill 事件累加 - 反向 slot fill 抵消)
pending_open_qty  = 该侧当前挂着的**加仓方向**单 qty 之和（排除平仓单）
                  = Σ (slot.currentOrderId != null 且 slot 是加仓 slot) × qty_per_grid
                  区分方法：
                    Neutral:  BUY 挂单 → LONG 加仓；SELL 挂单 → SHORT 加仓
                    Long:     BUY 挂单 → LONG 加仓；SELL 挂单是平仓（has_position=1），不计入
                    Short:    SELL 挂单 → SHORT 加仓；BUY 挂单是平仓（has_position=1），不计入

if side_position_qty + pending_open_qty > max_position_qty:
    动作: 停挂该侧新的 status→BUY/SELL 翻转（对应 grid update 生成 intent 时跳过）
    已挂的其他侧 slot 单继续正常运作
```

**为什么加 pending**：如果只看 side_position_qty，累计到快到上限时又挂 10 个 BUY，未来一秒钟全部 fill 就一下超上限。加 pending 是**保守估算最坏情况**。

**取值建议**：`max_position_qty ≈ grid_count × qty_per_grid × 1.0`（默认等于网格容量）。V2 里**必填**，NULL 抛验证错误。若设置 > grid_count × qty_per_grid 永远达不到（网格挂满就是容量上限），只是安全冗余；设置 < 则实际有效容量降低。

**极端场景考虑**：
- 用户设 `max_position_qty` 小于 `grid_count × qty_per_grid` → 网格永远无法挂满，正常
- 用户设为 0 → 完全不能挂 slot 加仓单（等价于停策略），启动时校验拒绝

### 4.2 账户级 PNL / ROI 阈值（opt-in）
```
if risk_auto_stop_enabled == 1:
    if account_pnl / investment < -pnl_stop_pct:
        动作: emergencyStop（撤单 + 可选平仓）
```

**默认关闭**（V1 里已经做了 opt-in 修复，V2 沿用）。

### 4.3 保命兜底（不可关）
- 币安 MARGIN_CALL 事件 → 立即 emergencyStop
  - **ISOLATED 保证金**：只停该 symbol 的策略（保证金独立，其他 symbol 不受影响）
  - **CROSS 保证金**：停整账户所有 LIVE 策略（保证金共享，一处失守全账户风险）
  - 由 `AccountRiskV2` 根据 strategy.margin_type 分派动作
- 价格突破 upper/lower → 停加仓（是硬止损不是硬平仓）

### 4.4 Emergency stop 撤单/平仓顺序

Stop Trigger / MARGIN_CALL / 账户 PnL 触发时，`EmergencyStopExecutor` 动作序列：
```
1. 状态改 STOPPING（阻止新 intent 提交，见 §17.4）
2. **并发同时执行**：
   a. 撤所有活跃挂单（batchCancel 10/req）
   b. 若 stop_close_position=1：立即下 MARKET 平仓单（role=MARKET, mm2-emerg- 前缀）
   （并发比串行安全：串行"先撤再平"期间价格移动可能触发额外 fill，加大风险敞口）
3. 等两条链路都完成（超时 10s，超时告警）
4. 状态改 EMERGENCY_STOPPED
5. 发 StrategyEmergencyStoppedEvent
```

**为什么并发不是原子**：币安 batchCancel 和 MARKET 单是两个 REST 端点，无法原子。但**并发下发比串行等待更安全**（缩短暴露窗口）。极端情况下：撤单期间已挂 BUY 单 fill → PositionAccountant 更新 pos → MARKET 平仓单 qty 已按最新 pos 计算（下一步的 open_position 查询会拿到最新）。

**移除的（相对 V1）**：
- ❌ 5 层 Layer 状态机
- ❌ ProactiveReducer 追价 24 次
- ❌ SideStopService 分侧独立停
- ❌ LadderCloseScheduler 梯队平仓
- ❌ 多策略联动熔断

---

## 五、V2 数据模型

### 5.1 grid_strategy_v2（新表）

```sql
CREATE TABLE `grid_strategy_v2` (
    id                        BIGINT PRIMARY KEY,
    name                      VARCHAR(128),
    exchange                  VARCHAR(32) DEFAULT 'binance-usdcm',
    symbol                    VARCHAR(64) NOT NULL,
    account_id                BIGINT NOT NULL,
    status                    VARCHAR(24) DEFAULT 'STOPPED',  -- STOPPED / RUNNING / REBUILDING / STOPPING / EMERGENCY_STOPPED（见 §17.4）
    execution_mode            VARCHAR(16) DEFAULT 'PAPER',

    -- ── 核心网格参数（币安模型）
    direction                 VARCHAR(16) NOT NULL,  -- LONG / SHORT / NEUTRAL
    grid_mode                 VARCHAR(16) NOT NULL,  -- ARITHMETIC / GEOMETRIC
    lower_price               DECIMAL(20,8) NOT NULL,
    upper_price               DECIMAL(20,8) NOT NULL,
    grid_count                INT NOT NULL,          -- 2 ~ 1000
    investment                DECIMAL(20,8) NOT NULL,-- 初始投入 USDC
    leverage                  INT DEFAULT 3,
    margin_type               VARCHAR(16) DEFAULT 'ISOLATED',
    open_initial_position     TINYINT DEFAULT 0,     -- LONG/SHORT 是否开初仓（NEUTRAL 忽略）

    -- ── Stop Triggers（可选，都填了取最严的先触发）
    stop_price_upper          DECIMAL(20,8),         -- 到达即停
    stop_price_lower          DECIMAL(20,8),
    stop_pnl_upper            DECIMAL(20,8),         -- 累计盈利到 X USDC 止盈
    stop_pnl_lower            DECIMAL(20,8),         -- 累计亏损到 -X USDC 止损
    stop_roi_pct_upper        DECIMAL(6,2),          -- ROI% 止盈
    stop_roi_pct_lower        DECIMAL(6,2),          -- ROI% 止损
    stop_close_position       TINYINT DEFAULT 1,     -- 触发时是否平仓（0=仅撤单保仓）

    -- ── Trailing（见 §2.6，可选 opt-in，纯平移不重算 step）
    trailing_upper_enabled    TINYINT DEFAULT 0,
    trailing_lower_enabled    TINYINT DEFAULT 0,
    trailing_upper_offset_pct DECIMAL(4,2) DEFAULT 0.5,
    trailing_lower_offset_pct DECIMAL(4,2) DEFAULT 0.5,

    -- ── 差异化增强（可选）
    step_multiplier_enabled   TINYINT DEFAULT 0,     -- 打开才计算 X（见 §16）
    step_multiplier_max_x     DECIMAL(4,2) DEFAULT 0.5, -- X 上限
    step_multiplier_mode      VARCHAR(16) DEFAULT 'REPORT_ONLY',  -- REPORT_ONLY / SPARSE（α1 只手动 shiftWindow 触发）
    smart_pricing_enabled     TINYINT DEFAULT 1,
    smart_pricing_min_liq     DECIMAL(20,8) DEFAULT 2.0,

    -- ── Dynamic Order Mechanism（活跃窗口，见 §14）
    active_window_size        INT,                   -- NULL=全挂，非空=只挂 mark 附近 ±N 挡
    active_window_buffer_slots INT DEFAULT 3,        -- 边缘 buffer（抗抖动）
    window_slide_min_move_pct DECIMAL(4,2) DEFAULT 0.5,
    adaptive_tick_enabled     TINYINT DEFAULT 0,     -- 高波动时 30s → 5s
    fill_driven_slide_enabled TINYINT DEFAULT 1,     -- fill 后立即评估滑动

    -- ── 硬风控
    max_position_qty          DECIMAL(20,8),         -- NULL = 无硬上限（不推荐）
    risk_auto_stop_enabled    TINYINT DEFAULT 0,     -- opt-in 账户级
    account_pnl_stop_pct      DECIMAL(4,2) DEFAULT 5.0,

    -- ── 输入模式（前端表单恢复用，见 §13.7）
    input_mode                VARCHAR(24) DEFAULT 'INVESTMENT_DRIVEN',  -- INVESTMENT_DRIVEN / STEP_DRIVEN
    qty_per_grid              DECIMAL(20,8),         -- A 模式派生存下，B 模式用户填

    -- ── Paper 模拟
    paper_queue_loss_factor   DECIMAL(4,2) DEFAULT 0.5,
    paper_gtx_reject_rate     DECIMAL(4,2) DEFAULT 0.02,

    -- ── 运行时快照（由 PnlSnapshotScheduler 1min sync）
    current_position_qty      DECIMAL(20,8) DEFAULT 0,
    current_realized_pnl      DECIMAL(20,8) DEFAULT 0,
    current_unrealized_pnl    DECIMAL(20,8) DEFAULT 0,
    current_effective_step_pct DECIMAL(8,4),         -- 当前 slot 拓扑派生的 step_pct；α1 重建时刷新
    current_x_value           DECIMAL(4,2) DEFAULT 0, -- 最近一次 X（监控用）
    last_shift_at             DATETIME,              -- Trailing 平移时刷新
    last_rebuild_at           DATETIME,              -- α1 手动 shiftWindow 拓扑重建时刷新
    shift_window_count        INT DEFAULT 0,         -- 手动 shiftWindow 累计触发次数
    trailing_shift_count      INT DEFAULT 0,         -- Trailing 累计触发次数
    started_at                DATETIME,

    create_by, create_time, update_by, update_time, deleted
);
```

**字段数**：约 40 个（V1 是 60+），砍掉了所有 Layer / depletion 阈值 / reduce_pct 等未使用的。

### 5.2 grid_slot_v2（新表，替代 SlidingWindow 内存 slot）

```sql
CREATE TABLE `grid_slot_v2` (
    id                    BIGINT PRIMARY KEY,
    grid_strategy_id      BIGINT NOT NULL,
    grid_index            INT NOT NULL,             -- 0..grid_count
    price                 DECIMAL(20,8) NOT NULL,
    status                VARCHAR(16) NOT NULL,     -- BUY / SELL / OFF（当前实际挂单状态）
    has_position          TINYINT DEFAULT 0,        -- 该 slot 曾被 fill 且当前持仓未平（Long/Short 判定该挂平仓单用）
    current_order_id      BIGINT,                   -- 当前挂的 grid_order_v2.id (nullable)
    last_fill_at          DATETIME,
    fill_count            INT DEFAULT 0,
    UNIQUE KEY (grid_strategy_id, grid_index)
);
```

**每档一行**。字段语义：
- `status`：当前实际挂单状态（BUY/SELL/OFF）
- `has_position`：Long/Short 独有（Neutral 里始终 0）—— 该 slot 当前持有 1 grid 仓位未平；在 Long grid 里意味着"该 slot 是加仓入口，需要 has_position ? SELL 平仓单 : BUY 加仓单"
- **维护时机**：
  - 加仓 fill（Long BUY / Short SELL）→ has_position 从 0 → 1
  - 平仓 fill（Long SELL / Short BUY）→ has_position 从 1 → 0
  - Trailing / α1 重建 → 从 positionRisk 反推整表分布（见 §2.6 步骤 4 / §16.2 α1）
  - Recovery → rehydrate + positionRisk 一致性校验（见 §7.4）

**为什么不存 `desired_status`**：中枢 `k` 随 mark 动态变化，desired_status 会跟着变，存表反而要维护同步。改由 SlidingActiveWindowService 在挂单决策时按公式派生：
```
computeDesiredSide(direction, slot.idx, k, has_position):
  if slot.idx == k:  return OFF
  Neutral:  slot.idx < k → BUY;  slot.idx > k → SELL
  Long:     slot.idx < k → BUY;  slot.idx > k → (has_position ? SELL : OFF)
  Short:    slot.idx > k → SELL; slot.idx < k → (has_position ? BUY  : OFF)
```

恢复时从这里 rehydrate（status + has_position + current_order_id）。α1 拓扑重建时整表清空重建。

### 5.3 grid_order_v2（沿用 V1 结构，加 `slot_id`）

- 加 `slot_id BIGINT NOT NULL` → 关联到 `grid_slot_v2.id`
- **不允许 NULL**：V2 每个订单都来自某个 slot 翻转（方案 α 无独立 CLOSE 单）
- 其他字段不变（P&L 归因需要 filled_qty / avg_fill_price / status 等）
- 删除 `pair_order_id` 字段（V2 P&L 算法不再用 FIFO 配对，见 §15）

**role 字段的语义变化**：
- V1：`OPEN` / `CLOSE`（区分开仓/平仓）
- V2：`GRID`（所有 grid slot 单都是 GRID）+ `MARKET`（emergency 强平时用）
  - GRID 单是开仓还是平仓由"当前 side_position_qty"决定，不由 role 决定

**唯一约束**：`(strategy_id, exchange_order_id) UNIQUE`（跨策略防重）。

---

## 六、V2 组件架构（事件驱动 + 按能力分包）

### 6.1 架构原则

1. **事件驱动为主，轮询为辅**：跨业务领域通信走 event bus，同领域内直接方法调用
2. **按业务能力分 sub-package**：不按 entity/mapper/service 技术分层
3. **每个包 3-5 个类**：单一职责，可独立测试、可选加载
4. **依赖单向**：event 定义在最底层，业务包依赖 event，反过来不可以
5. **可替换**：换 stop trigger 实现只改 `trigger/` 包，不动核心

### 6.2 模块顶层结构

```
mm-service-v2/
├── binance/                    ← 100% 沿用 V1（Binance REST + SymbolFilters + TimeSync）
├── market/                     ← 100% 沿用 V1（3 个 WS Subscriber + Cache + SmartPricer）
├── userstream/                 ← 100% 沿用 V1（ListenKey + UserDataStream）
├── paper/                      ← 100% 沿用 V1（PaperOrderBook + FillSimulator）
└── grid_v2/                    ← 全新，按业务能力分 12 个 sub-package
```

### 6.3 grid_v2 内部结构（12 个 sub-package）

```
grid_v2/
├── entity/                     数据实体
│   ├── GridStrategyV2
│   ├── GridSlotV2
│   └── GridOrderV2
├── mapper/                     持久化
├── dto/                        请求 DTO
├── vo/                         响应 VO
├── controller/                 REST 入口
│   └── GridV2Controller
│
├── event/                      ★ 事件定义 & 分发（最底层，其他包都依赖）
│   ├── GridEvent               sealed interface (Java 17+)
│   ├── FillEvent               成交事件（含 slot 上下文）
│   ├── PriceMoveEvent          市场价变动（500ms throttle）
│   ├── StopTriggeredEvent      Stop Trigger 命中
│   ├── PositionLimitReachedEvent  仓位到上限
│   ├── AccountEmergencyEvent   账户级熔断
│   ├── WindowSlideNeededEvent  活跃窗口需滑动
│   ├── StepMultiplierChangedEvent  X 因子变更
│   └── GridEventBus            facade（基于 Spring ApplicationEventPublisher）
│
├── lifecycle/                  ★ 策略生命周期
│   ├── GridV2Service           start/stop 编排
│   ├── GridRecoveryV2          JVM 重启恢复
│   ├── EmergencyStopExecutor   紧急停机（撤单 + 可选平仓；由 GridV2Service.emergencyStop 调用，非独立入口）
│   ├── ShiftWindowExecutor     手动 shiftWindow 触发拓扑重建（α1，见 §16）
│   ├── TrailingExecutor        Trailing 纯平移（新 lower/upper，保持 grid_count）
│   └── TrailingMonitor         订阅 PriceMoveEvent，mark 越界 & trailing_*_enabled=1 时触发 TrailingExecutor
│
├── grid/                       ★ 网格核心
│   ├── GridEngineV2            UserStream OrderTradeUpdate 入口，转发 mm2-* 前缀事件为 FillEvent 发布
│   ├── GridBuilder             init 拓扑构建（也承担 α1 rebuild）
│   ├── GridStateManager        slot 内存状态（含 has_position 维护）
│   ├── GridUpdateHandler       响应 FillEvent → slot 翻转 + 生成 intent
│   └── GridSnapshotSyncer      1min 内存 → DB sync
│
├── window/                     ★ Dynamic Order Mechanism
│   ├── SlidingActiveWindowService
│   └── WindowSlideListener     响应 PriceMoveEvent + FillEvent
│
├── trigger/                    ★ 3 种 Stop Trigger
│   ├── StopTriggerEvaluator    聚合入口，30s 定时 + 事件驱动双触发
│   ├── PriceStopChecker
│   ├── PnlStopChecker
│   └── RoiStopChecker
│
├── guard/                      ★ 硬风控
│   ├── PositionLimitGuard      拦 intent 前检查累积仓位
│   ├── PriceBoundaryGuard      upper/lower 边界检查
│   ├── AccountOrderQuotaGuard  同 symbol 挂单总数 >195 拒挂（§14.7）
│   └── AccountRiskV2           账户级 pnl / MARGIN_CALL
│
├── execution/                  ★ 下单执行
│   ├── GridOrderRouter         Paper/Live 路由（沿用 V1）
│   ├── GridIntentExecutor      batch 5/10 + 落 DB
│   └── OrderPersistence
│
├── signal/                     ★ 可选信号增强（@ConditionalOnProperty）
│   ├── TrendScoreCalculator    输出 X ∈ [0, maxX]
│   ├── StepMultiplierService   按 mode 分发 X 生效路径（REPORT_ONLY 只发监控 / SPARSE 通知 window/ / α1 手动 shiftWindow 触发拓扑重建）见 §16
│   └── DepletionSensor         fill 速率 + 不对称
│
└── pnl/                        ★ P&L 归因（详见 §15）
    ├── PositionAccountant      加权平均成本 avg_cost + realized_pnl（替代 V1 OrderPairMatcher）
    ├── PnlSnapshotService      1min 采样 unrealized
    └── FundingIncomeService    15min 拉 income endpoint
```

**共 12 个 sub-package，40+ 类**，每包 3-5 类，边界清晰。

### 6.4 事件拓扑（事件产生者 vs 消费者）

**Producers（谁发出事件）**：

| 事件 | 发出者 | 触发时机 |
|---|---|---|
| `FillEvent` | LIVE：`GridEngineV2`（`grid/` 内 UserStream 入口，filter mm2-）／ PAPER：`PaperFillSimulator` 直接发布 | LIVE=OrderTradeUpdate 到达；PAPER=模拟撮合命中 |
| `PriceMoveEvent` | `PriceMoveThrottler`（在 `market/` 里，500ms 节流） | BookTicker 更新且 mark 变化 > tick |
| `StopTriggeredEvent` | `StopTriggerEvaluator` | 定时 tick 或 PriceMoveEvent 命中阈值 |
| `PositionLimitReachedEvent` | `PositionLimitGuard` | 拦挂单时发现超上限 |
| `AccountEmergencyEvent` | `AccountRiskV2` | MARGIN_CALL 或账户 pnl 越阈值 |
| `WindowSlideNeededEvent` | `WindowSlideListener` | PriceMoveEvent 后 mark 离窗口边缘 < N |
| `StepMultiplierChangedEvent` | `StepMultiplierService` | 5min tick 后 X 变化 |

**Consumers（谁订阅事件）**：

| 消费者 | 订阅事件 | 动作 |
|---|---|---|
| `GridUpdateHandler` | FillEvent | 翻转 slot 状态 → 相邻 slot 补挂 intent → 走 executor |
| `PositionAccountant` | FillEvent | 更新 avg_cost / pos / realized_pnl（见 §15）|
| `DepletionSensor` | FillEvent | 更新 fills 队列、速率、不对称 |
| `SlidingActiveWindowService` | WindowSlideNeededEvent / StepMultiplierChangedEvent(SPARSE 模式) | 撤远单 + 按 X 稀疏挂近单 |
| `StopTriggerEvaluator` | PriceMoveEvent | 检查 Price Stop 阈值 |
| `EmergencyStopExecutor` | StopTriggeredEvent / AccountEmergencyEvent | 撤单 + 可选平仓 → 状态改 STOPPED |
| `TrailingMonitor` | PriceMoveEvent | mark 越界 + trailing_upper/lower_enabled=1 → 调 TrailingExecutor 纯平移 |
| `GridV2Service` | PositionLimitReachedEvent | 通知前端 + log |

### 6.5 数据流图

```
─────────────────  Binance WS  ─────────────────
     │                              │
OrderTradeUpdate               AggTrade / BookTicker
     │                              │
     ▼                              ▼
GridEngineV2                  MarketQuoteCache
  (grid/)                     (market/)
     │                              │
     │ 发出                          │ 发出（节流）
     ▼                              ▼
   FillEvent                   PriceMoveEvent
     │                              │
     ├─────────────┬────────────┬───┴──────┬─────────────┐
     ▼             ▼            ▼          ▼             ▼
 GridUpdate   Position       Depletion  Window     StopTrigger
  Handler     Accountant      Sensor     Slide      Evaluator
  (grid/)     (pnl/)        (signal/)   (window/)   (trigger/)
     │            │            │           │             │
     │ 生成       │ 更新        │ 更新       │ 撤+挂        │ 命中?
     ▼            ▼            ▼           ▼             ▼
   Intent      avg_cost     depletion   API 调用    StopTriggeredEvent
              realized_pnl
     │                                                    │
     ▼                                                    ▼
 PositionLimitGuard                              EmergencyStopExecutor
 (guard/)                                        (lifecycle/)
     │ 通过?                                             │
     ▼                                                    ▼
 PriceBoundaryGuard                              撤单 + 可选平仓
 (guard/)
     │ 通过?
     ▼
 GridOrderRouter → PaperOrderClient / MmOrderClient
 (execution/)          (paper/)         (binance/)
     │
     ▼
 OrderPersistence → grid_order_v2
```

**关键**：核心业务链路 **event → handler → intent → guard → execution** 全部通过事件解耦。想插入新逻辑（比如"到某价发送邮件通知"），只需新建一个 `@EventListener` 订阅现有 event，不动任何现有代码。

### 6.6 定时任务（少量必要的轮询）

事件驱动无法覆盖的场景用 4 个 scheduler 兜底：

| Scheduler | 频率 | 职责 | 事件驱动补足 |
|---|---|---|---|
| `StopTriggerEvaluator.periodicCheck` | 30s | 定时兜底（防事件驱动漏）| PriceMoveEvent 已实时触发 |
| `PnlSnapshotService.tick` | 1min | 采样 + 内存 → DB sync | 无 |
| `AccountRiskV2.periodicEvaluate` | 1min | 检查账户浮亏 | 事件驱动无法定期算 |
| `StepMultiplierService.tick` | 5min | 算 X（依赖 K 线，本身就是低频）| 无 |

**只 4 个 scheduler**（V1 有 7 个），且大部分是"兜底"性质，主逻辑走事件。

### 6.7 依赖方向（无循环）

```
event/  ←─── 所有业务包依赖它（只依赖事件定义）
   ↑
   │ 发布事件
   │
grid/, window/, trigger/, guard/, signal/, pnl/, lifecycle/
   │
   ├─→ execution/  (下单)
   ├─→ entity/, mapper/  (持久化)
   └─→ ../binance, ../market, ../userstream, ../paper  (基础设施)

controller/ ─→ lifecycle/, dto/, vo/
lifecycle/  ─→ grid/, execution/, event/

从不反向：event/ 不依赖任何业务包
业务包之间通过 event 通信，不直接互相 @Autowired
```

### 6.8 可选加载（@ConditionalOnProperty）

```
signal/ 整个包 → @ConditionalOnProperty("vertex.mm.v2.signal.enabled")
window/SlidingActiveWindowService → @ConditionalOnProperty("vertex.mm.v2.dynamic-order.enabled")
pnl/FundingIncomeService → @ConditionalOnProperty("vertex.mm.v2.funding-pnl.enabled")
```

关闭后对应 bean 不加载，性能开销降为 0。

### 6.9 相对 V1 的架构收益

| 维度 | V1 | V2 |
|---|---|---|
| 主服务类的行数 | GridStrategyService 400+ 行 | GridV2Service <150 行（只做编排）|
| 组件耦合方式 | Service 相互 @Autowired 调 | Event bus 解耦 |
| 添加新能力（如"到某价发通知"）| 改 GridEngine + 加 service | 新建 1 个 @EventListener |
| 单测复杂度 | 需 mock 5+ 依赖 | 单个 handler 只测"事件 → 输出" |
| Scheduler 数量 | 7 个 | 4 个 |
| 代码总行数 | ~3800 | 预计 ~2400（-40%）|
| 概念数量 | 60+ 字段 + 5 层 Layer | 40 字段 + 2 硬触发 |

### 6.10 组件与文档章节的映射

| Sub-package | 对应本文档章节 |
|---|---|
| `grid/` | §2.3 Grid Update + §5.2 grid_slot_v2 + §7.1-7.2 |
| `window/` | §14 Dynamic Order Mechanism |
| `trigger/` | §2.4 Stop Trigger |
| `guard/` | §四 V2 风控 |
| `signal/` | §三 差异化增强 + §16 Step Multiplier |
| `pnl/` | §15 P&L 归因算法（V2 加权平均成本）|
| `lifecycle/` | §7 核心流程 |
| `execution/` | §V1 完全复用 |
| `event/` | 本章 §6.4-6.5 |

每个包实现前先看对应章节。

---

## 七、核心流程

### 7.1 启动

```
POST /admin/mm/grid-v2/{id}/start
     ↓
GridV2Service.start(id)
  ├─ 校验：upper > lower, grid_count 2~1000, investment > 0
  ├─ 拉 SymbolFilters, tick/step 对齐
  ├─ 订阅 WS (bookTicker + depth)
  ├─ 计算：per_grid_qty = investment × leverage / grid_count / avg_price
  ├─ GridBuilder.build()：
  │    ├─ 生成 grid_count 个价格档 slot_0..slot_{N-1}
  │    ├─ 找到 mark 附近的 grid_index k
  │    ├─ 每个 slot：has_position=0；status 按 direction 和 active_window 决定
  │    │    - Neutral 全挂：slot_0..slot_{k-1}.status=BUY, slot_{k+1}..slot_{N-1}.status=SELL, slot_k=OFF
  │    │    - Long 全挂：   slot_0..slot_{k-1}.status=BUY, 其他 OFF
  │    │    - Short 全挂：  slot_{k+1}..slot_{N-1}.status=SELL, 其他 OFF
  │    │    - 若 active_window_size 非空：只有活跃窗口内的按上面挂，窗口外 status=OFF
  │    └─ 写 grid_slot_v2 表 + 生成 intents
  ├─ LIVE：setDualSideMode + setLeverage + setMarginType
  ├─ persistRunning（先持久化再挂单，crash-safe）
  ├─ router.batchPlace（分批 5 单）
  └─ track clientOrderId
```

### 7.2 成交（Grid Update）

```
Fill 事件到达 GridEngineV2
  ├─ 找到对应 grid_slot_v2（通过 clientOrderId → order → slot_id）
  ├─ slot_j.status: BUY/SELL → OFF
  ├─ has_position 更新（Long/Short 独有；Neutral 里始终 0）：
  │    ├─ 加仓 fill（Long BUY / Short SELL）→ slot_j.has_position = 1
  │    └─ 平仓 fill（Long SELL / Short BUY）→ slot_j.has_position = 0
  ├─ **PositionAccountant 先更新 avg_cost + realized_pnl（含 fee 抵扣，见 §15）**
  │    —— 必须先更新，因为下一步 PositionLimitGuard 依赖最新 side_position_qty
  ├─ 生成相邻 slot 补挂 intent（若 slot_{j±1}.status=OFF）：
  │    ├─ 若 slot_j 是 BUY fill → intent: 挂 SELL @ slot_{j+1}
  │    └─ 若 slot_j 是 SELL fill → intent: 挂 BUY @ slot_{j-1}
  ├─ **intent 前置 guard 链**（intent 未落 order table 前依次检查，任一 fail 则丢弃 intent）：
  │    ├─ PriceBoundaryGuard: 挂单价必须在 [lower_price, upper_price] 内
  │    ├─ PositionLimitGuard: 若是加仓方向 intent 且 (side_position_qty + pending_open_qty + qty_per_grid) > max_position_qty → 丢弃
  │    │   （平仓方向 intent 不受 max 约束）
  │    └─ AccountOrderQuotaGuard: 若 symbol 总挂单 ≥ 195 → 丢弃 + 告警
  ├─ intent 通过 → GridIntentExecutor 挂单 + 更新 slot.status/has_position/currentOrderId
  │    Long/Short 里 相邻 SELL 挂上时 slot_{j+1}.has_position = 1
  │    Long/Short 里 相邻 BUY 挂上时 slot_{j-1}.has_position = 0
  └─ StopTriggerEvaluator.checkAndTrigger（若达到 stop 阈值 → emergencyStop）
```

### 7.3 停止（正常）

```
POST /admin/mm/grid-v2/{id}/stop
  ├─ 撤所有 LIVE 挂单
  ├─ 若配了 stop_close_position=1 → 平所有仓（下 MARKET 单，role=MARKET，clientOrderId 前缀 mm2-emerg-）
  ├─ 若 stop_close_position=0 → 保仓
  ├─ 状态改 STOPPED
  └─ WS 退订（refCount）
```

### 7.4 崩溃恢复

```
JVM 启动 → GridRecoveryV2
  ├─ PAPER:
  │    ├─ 状态改 STOPPED（PAPER 不做静默续跑）
  │    ├─ markLiveOrdersCanceled：把 grid_order_v2 里 LIVE/PARTIAL 的 PAPER 单标为 CANCELED
  │    ├─ cleanupStalePaperOrders：清 PaperOrderBook 内存里对应 strategy 的挂单
  │    ├─ PaperOrderBook.idGenerator seed from DB max(grid_order_v2.exchange_order_id)  ← 防重启后 id 冲突
  │    └─ slot 状态重置到 OFF（PAPER 重启视为新开始）
  └─ LIVE:
       ├─ 从 grid_slot_v2 rehydrate slot 拓扑 (status + has_position + current_order_id)
       ├─ 拉 positionRisk 同步真实持仓 → 一致性校验：
       │    Σ has_position × qty_per_grid vs positionRisk 净持仓
       │    偏差 < 1 grid → 忽略（正常尾差）
       │    偏差 ≥ 1 grid → 交易所权威，触发 has_position 反推（同 §2.6 步骤 4）+ 告警
       ├─ 拉 openOrders 反向对账（mm2- 前缀 → slot.current_order_id；其他 → 标 UNKNOWN_RECONCILE）
       ├─ 重订 WS，重启 UserStream
       ├─ 立即调 slideActiveWindow(strategy, mark) 补齐活跃窗口内缺的挂单（见 §14.8）
       └─ 事件 RECOVERY_LIVE_OK
```

---

## 八、V1 → V2 迁移路径

**不做原地重写，用双系统平滑过渡**：

### 迁移步骤

1. **V1 冻结**（仅接受 bug 修复，不加新功能）
2. **V2 新建 Gradle 子模块** `mm-service-v2` 或者同 module 加 `grid_v2` 子包
3. **DB 新表**（V33 SQL）：`grid_strategy_v2`, `grid_slot_v2`, `grid_order_v2` 独立于 V1
4. **前端加 tab**：策略列表分 "V1 (legacy)" 和 "V2" 两个视图
5. **共用组件**：`MmOrderClient` / `BinanceAccountClient` / `BookTickerSubscriber` / `PaperOrderClient` 等基础层完全共用
6. **V2 跑 PAPER 至少 1 周稳定** → 迁移一个 LIVE 策略试点 → 全面切换
7. **V1 策略人工迁移**：用户在 V1 stop 后手动在 V2 新建同参数策略
8. **V1 6 个月后废弃**：`@Deprecated` 标注 → 下个大版本删除

### 数据不迁移

V1 grid_strategy / grid_order 表**保留只读**，方便历史分析。V2 用全新表。

### 组件复用清单

**100% 复用**：
- `MmOrderClient` / `MmOrderRequest` / `MmOrderResult`
- `BinanceAccountClient` / `BinanceTimeSync` / `SymbolFilters(Cache)`
- `BookTickerSubscriber` / `DepthSubscriber` / `AggTradeSubscriber` / `MarketQuoteCache` / `OrderBookCache` / `SmartPricePlacer`
- `ListenKeyManager` / `UserDataStreamManager` + event 类
- `PaperOrderClient` / `PaperOrderBook` / `PaperFillSimulator` / `PaperAccountState`
- `AccountCredentialResolver`
- `WsConnectionWatchdog`
- 全部前端 API/UI 基础组件（Antd 表单/表格/Recharts 等）

**大幅简化后复用**：
- `TrendScoreEvaluator` → `TrendScoreCalculator`（只输出 X 单值）
- `WindowDepletionAnalyzer` → 减掉 speed/asymmetry 复杂度，只出 depletion pct

**弃用**：
- `SlidingWindowManager`（V2 用 `GridBuilder` + `grid_slot_v2` 表持久化）
- `AdaptiveScheduler`（V2 用 `lifecycle/TrailingMonitor` opt-in，事件驱动而非定时）
- `LadderCloseScheduler` + `LadderCloseManager`
- `ProactiveReducer`
- `SideStopService`
- `AccountRiskAggregator` → 简化为 `AccountRiskV2`（去掉 layer3 联动规则）
- `EmergencyStopService`（V2 拆为 `lifecycle/EmergencyStopExecutor` 作为动作执行器，由 `GridV2Service.emergencyStop(reason, mode)` 唯一入口调用；不再是独立可 @Autowired 的顶层 service）
- `GridEngine` / `GridStrategyService` → `GridEngineV2` / `GridV2Service` 重写
- `OrderPairMatcher` → 弃用（V2 用 §15 加权平均成本 `PositionAccountant`，不再 FIFO pair）
- `GridBacktestService` → **需要重写**（V1 的 rearm 语义与 V2 的 slot 翻转不兼容，Phase 7 里 Backtest V2 从零写）

### 关键适配注意事项

**⚠️ PaperFillSimulator 需要适配 method α**：
- V1 里 fill 后 rearm 是"同价再挂 BUY"（无 slot 概念）
- V2 里 fill 后是 slot 翻转（BUY→OFF, 相邻 slot OFF→SELL）
- Paper 撮合本身不变（仍是 aggTrade 驱动的价格穿越判定）
- 两条 FillEvent 发布路径（共用同一个 event bus 下游）：
  - LIVE：UserStream OrderTradeUpdate → `grid/GridEngineV2`（filter `mm2-` 前缀）→ publish `FillEvent`
  - PAPER：`PaperFillSimulator` 直接 publish `FillEvent`（clientOrderId 也带 `mm2-` 前缀，与 LIVE 语义一致）
- 下游消费者（`GridUpdateHandler` / `PositionAccountant` / `DepletionSensor` / `WindowSlideListener`）不关心来源，统一处理

**⚠️ Backtest V2 从零重写**：
- 不复用 `GridBacktestService`，因该组件深度耦合 V1 rearm-same-price 语义
- V2 backtest 结构：kline replay → mock price crosses grid → 触发 slot 翻转 → PositionAccountant 累计 P&L
- 输出 `BacktestResultV2`（arb/dir 定义按 §15.3）

### 部署与运维

**Docker/K8s**：
- V2 与 V1 共存于同一 Spring Boot jar；用 `vertex.mm.v2.enabled=true` 打开
- 需要新增两个 bean profile：`grid-v2-live` / `grid-v2-paper`
- K8s ConfigMap 里配 `VERTEX_MM_V2_ENABLED=true`
- 灰度：先在 dev/staging 打开，prod 保持关（V1 独占）→ Phase 5 完成后 prod 也打开
- LIVE 策略切换必须在 V2 完成 1 周 PAPER 稳定后

**监控（Prometheus）**：
```
mm_v2_slot_state{strategy_id, slot_index, status}    # 0=OFF 1=BUY 2=SELL
mm_v2_active_window_size{strategy_id, side}
mm_v2_grid_update_latency_seconds{strategy_id}        # slot flip → order placed
mm_v2_position_qty{strategy_id, side}
mm_v2_realized_pnl_total{strategy_id, type}           # type=arb|dir|funding
mm_v2_unrealized_pnl{strategy_id}
mm_v2_step_multiplier{strategy_id}                    # 当前 X
mm_v2_trailing_shift_total{strategy_id, direction}    # up|down
mm_v2_stop_trigger_hit_total{strategy_id, kind}       # price|pnl|roi
mm_v2_gtx_rejected_total{strategy_id}
mm_v2_batch_place_latency_seconds{strategy_id}
mm_v2_reconcile_diff_orders{strategy_id}              # local vs 交易所差异
```

Grafana 面板复用 V1 结构，只是 metric 名换 `mm_v2_*`。告警规则：
- `mm_v2_reconcile_diff_orders > 0` for 5min → warn
- `mm_v2_stop_trigger_hit_total` 突增 → info
- `mm_v2_grid_update_latency_seconds` p99 > 2s → warn

**权限**：
- 新增 permission code `mm:grid-v2`（read/write/emergency 三档）
- V1 权限 `mm:grid` 不变；用户可只授一个或都授
- UI 菜单按 permission 显示对应 tab

**测试策略**：
- **Unit test**：GridBuilder 边界（首末档）/ Slot 翻转矩阵 / PositionAccountant 转向逻辑（LONG → SHORT）/ Stop Trigger priority
- **Integration test**：用 embedded MySQL + `MockMmOrderClient`，跑完整启动 → 5 次 fill → stop 流程
- **Backtest 回归**：3 个历史周期（震荡/单边/剧烈波动）跑 V1 vs V2 对比，差异必须能解释
- **Chaos test**：随机 kill service、模拟交易所 5xx、UserDataStream 断连 → recovery 必须一致

---

## 九、UI 变化

### 策略创建表单（大幅简化）

**Tab 1: 基础**
- 名称 / 交易对 / 账户下拉 / 方向（Long/Short/Neutral）/ 执行模式（PAPER/LIVE）

**Tab 2: 网格参数**
- Lower Price / Upper Price（必填，硬边界）
- Grid Count（2~1000）
- 模式（Arithmetic/Geometric）
- Investment（USDC 金额）
- Leverage / Margin Type
- Long/Short 时可选"是否开初仓"

**Tab 3: Stop Triggers（可选）**
- Price Stop：TP/SL 价（两个 InputNumber）
- PNL Stop：TP/SL 金额
- ROI% Stop：TP/SL 百分比
- 触发时行为：保仓 / 平仓

**Tab 4: 高级（默认折叠）**
- 打开动态步长 → 配 `step_multiplier_max_x` + `step_multiplier_mode`（REPORT_ONLY / SPARSE，见 §16）
- Dynamic Order Mechanism（见 §14）：
  - `active_window_size`（NULL=全挂）+ `buffer_slots`（默认 3）
  - Fill 驱动即时滑动（推荐开）
  - Adaptive tick（高波动缩短）
- SmartPricing 深度阈值 `smart_pricing_min_liq`
- Trailing Up/Down opt-in + `trailing_upper/lower_offset_pct`
- Max Position Qty 硬上限（必填）
- 账户级 pnl 自动停机 opt-in

**Tab 5: PAPER 参数**
- queue loss factor / gtx reject rate

### 详情页额外按钮
- 「手动 shiftWindow」按钮（走 §16.7 α1 重建，二次确认弹窗告知开销）

### 详情页

- Mark / Range (lower~upper) / Grid Count / Current Slot (mark 落在哪档)
- Realized PnL / Unrealized PnL / ROI% / Position Qty
- 挂单矩阵：按 grid_index 从下到上列出，标注 status (BUY 绿 / SELL 红 / OFF 灰)
- Stop Trigger 进度条（例如"距离 stop_roi_pct 还剩 3.2%"）
- 事件流水

---

## 十、未决问题

### Q1: 数据模型的向后兼容
V2 完全独立新表，V1 数据不迁移。用户历史策略只能人工在 V2 新建重跑。**接受**？

### Q2: 前端是否 V1/V2 完全分开
Option A：两个独立菜单项 "网格 V1 (Legacy)" 和 "网格 V2"
Option B：同一个菜单，参数配置时选版本

**推荐 A**：清晰、不误操作。

### Q3: SmartPricer + Depletion 保留是否会污染币安语义
V2 说是"币安模型 + 3 层增强"，但增强会改变 grid update 时机（例如 SmartPricer 把价格从档 N 调到档 N+1，slot 拓扑就乱了）。

**方案**：所有增强都必须**默认关闭**，用户显式打开才生效。默认状态 = 纯币安语义。

### Q4: 是否要在 V2 里做真正的分批挂单 (Dynamic Order Mechanism)
**已决议 → §14 详设完成**：改为显式配置 `active_window_size`（NULL=全挂，非空=只挂 ±N 挡 + buffer）。
- 网格 ≤ 20 档：默认 NULL 全挂
- 网格 > 50 档：建议启用 dynamic
- 多策略共享 200 挂单配额：任何 grid_count 都可强制开
- Phase 划分：`SlidingActiveWindowService` 放在 Phase 3（详见 §11）

### Q5: 步长倍数 X 的计算公式
```
X = w_depletion × max(long_depletion, short_depletion) 
  + w_adx × normalize(ADX, [20, 40])
  + w_asymmetry × log(max(1, |asymmetry|))
```
权重和归一化都需要 backtest 调优。**§16 已详设**：X 默认 `REPORT_ONLY` 只上报到 Prometheus，Phase 6 开启 SPARSE 生效路径。

### Q6: PAPER 模拟精度
V2 沿用 V1 的 PaperFillSimulator（撮合 bug 已修）。但仍是 aggTrade 驱动的简化模型。真实 LIVE 撮合会有 queue position、partial fill、rate limit 等 V1/V2 都没模拟的因素。**回测结果偏乐观 20-40%**（此数字源自 V1 pilot 期间 PAPER 与 LIVE 同参数策略实际 P&L 差异，是经验估算而非严格测量）。

V2 相较 V1 有几项精度改进：
- slot 拓扑约束让 fill 时机更接近币安撮合（不会 rearm 同价累积）
- SmartPricePlacer 微调让虚拟挂单价更贴真实 book
- 但 partial fill、GTX 拒单、queue position 仍未模拟 → 偏差下限约 10-15%

准确度校准：Phase 5 PAPER 跑通后，用 V1 现有 LIVE 数据做 backtest 对比校准偏差系数。

---

## 十一、Phase 划分

| Phase | 内容 | 工期 |
|---|---|---|
| **Phase 0** | V1 冻结 + V2 SQL + 空 module 骨架 + event bus + clientOrderId 前缀路由 | 1 天 |
| **Phase 1** | GridBuilder + slot 状态机（BUY/SELL/OFF）+ Grid Update 语义（α 翻转）+ PriceBoundaryGuard | 4-6 天 |
| **Phase 2** | 3 种 Stop Trigger + PositionLimitGuard(含 pending) + AccountRiskV2 + PositionAccountant | 3-4 天 |
| **Phase 3** | GridRecoveryV2(rehydrate slot) + SlidingActiveWindowService(dynamic) + WsReconciliationV2 + PnlSnapshotV2 | 3-4 天 |
| **Phase 4** | 前端 V2 表单 + 参数预览 API + 详情页 + 挂单矩阵 + 权限 mm:grid-v2 | 3-4 天 |
| **Phase 5** | PAPER 跑通 + PaperFillSimulator 适配 α（回调走 GridEngineV2）+ 修 bug | 3-5 天 |
| **Phase 6**（可选） | Trailing Up/Down opt-in + StepMultiplier X（signal/）+ SmartPricer 复用 | 2-3 天 |
| **Phase 7**（可选） | Backtest V2 从零重写（kline replay → slot 翻转 → PositionAccountant）+ 参数扫描 | 4-6 天 |

> **Phase 7 注意**：不复用 `GridBacktestService`（V1 rearm 语义与 V2 slot 翻转不兼容）。需要重写 `GridBacktestV2Service`，用 kline replay 驱动 slot 翻转，P&L 走 §15 `PositionAccountant`。

**总计 16-27 天**（不含 Phase 6/7）。可以并行前后端。

---

## 十二、V1 的教训（V2 要避免）

| V1 踩过的坑 | V2 预防 |
|---|---|
| 一次挂满 + rearm 同价 → 单侧可累积无限 | Grid Update 语义 = 每档一个状态 |
| 5 层 Layer 打分复杂难调 | 只 2 个硬触发 |
| 60+ 配置字段，用户不知道哪个真生效 | 40 个字段，UI 分 tab 清晰标注 |
| Fill 事件 vs GTX 拒单混在一起算 depletion | depletion 只统计 fill |
| Adaptive shift 撤挂单成本高 | X 默认 REPORT_ONLY 不撤挂；重挂只在 Trailing / 手动 shiftWindow 时发生（见 §16）|
| 账户级 pnl 5% 默认触发（隐藏坑）| opt-in + 默认关（沿用 V1 修复）|
| tick/step 未对齐 → -1013 拒单 | SymbolFilters 3 处生成端对齐（复用 V1）|
| Long 精度丢失 | 前端 id 用 string（复用 V1）|
| JVM 重启 PAPER 挂单变僵尸 | Recovery markLiveOrdersCanceled（复用 V1）|
| 高频写 DB | 内存权威 + 1min sync（复用 V1）|
| Layer 3/4 auto 触发从未接线 | 直接不做 5 层 |
| max_position_qty 字段填了不用 | 必填 + PositionLimitGuard 强制 |

---

## 十三、参数计算与 UX（金额 ↔ 步长双向）

### 13.1 两种录入模式

用户创建策略时可以选一种录入方式，另一组字段自动派生 + 实时预览。

| 模式 | 用户填 | 系统算 |
|---|---|---|
| **A. 金额驱动（默认）** | lower/upper/grid_count/investment/leverage | step_pct, per_grid_qty, per_grid_profit_pct |
| **B. 步长驱动** | lower/upper/step_pct/qty_per_grid/leverage | grid_count, required_investment |

**币安 UI 用的是模式 A**。用户直觉通常是"投 1000 USDC 在 2400-2600 区间跑"，不是"每格差 0.5%"。

### 13.2 金额驱动（模式 A）公式

**输入**：`L = lower_price, U = upper_price, N = grid_count, I = investment, lev = leverage`

```
total_notional     = I × lev
per_grid_notional  = total_notional / N

# 等比网格
ratio              = (U / L) ^ (1 / N)
step_pct           = (ratio - 1) × 100

# 等差网格
price_diff         = (U - L) / N
step_pct           = price_diff / L × 100

# 每档数量（用几何平均更精确）
avg_price          = sqrt(L × U)
per_grid_qty       = per_grid_notional / avg_price
per_grid_qty       = roundQty(per_grid_qty, symbol.stepSize)   # 按 symbol 对齐

# 每格净利润%（扣双向 maker 手续费；币安公式）
profit_per_grid    = (1 - fee_rate) × ratio - 1 - fee_rate      # 等比
                   ≈ ratio - 1 - 2 × fee_rate                    # 简化
```

### 13.3 步长驱动（模式 B）公式

**输入**：`L, U, step_pct = s, qty_per_grid = q, lev`

```
# 等比反推 grid_count
ratio              = 1 + s / 100
grid_count         = ceil(ln(U / L) / ln(ratio))

# 等差反推 grid_count
step_price_diff    = L × s / 100
grid_count         = ceil((U - L) / step_price_diff)

# 需要的保证金
avg_price          = sqrt(L × U)
per_grid_notional  = q × avg_price
total_notional     = per_grid_notional × grid_count
required_investment = total_notional / lev
```

### 13.4 校验规则（前后端一致）

用户填完立即校验，红字/黄字提示：

| 检查 | 条件 | 提示 |
|---|---|---|
| 区间有效 | `U > L` | "上界必须大于下界" |
| grid_count 上限 | 2 ≤ N ≤ 1000 | "网格数 2 ~ 1000" |
| 步长 ≥ tick | `step_pct × avg_price ≥ symbol.tickSize × minimumStepSafetyMultiplier` (默认 3，可配) | "步长过小（<{minStep}），减少 grid_count 或扩大区间" |
| per_grid_qty ≥ minQty | `per_grid_qty ≥ symbol.minQty` | "每格数量 {q} < 交易所最小 {minQty}，增加 investment 或减少 grid_count" |
| per_grid_notional ≥ minNotional | `per_grid_qty × avg_price ≥ symbol.minNotional (通常 5 USDC)` | "每格名义 {N} < 交易所最小 {minNotional} USDC" |
| 单笔过大 | `per_grid_qty × avg_price > 10000 USDC` | 黄字提示"单笔挂单较大，注意深度冲击"（不阻断）|
| 保证金覆盖 | `required_investment ≤ user_balance` (仅 B 模式)  | 红字"需要 {X} USDC，账户余额 {Y}，缺 {Z}" |

### 13.5 UI Wireframe（模式 A 默认展示）

```
┌─────────────────────────────────────────┐
│ 交易对: [ETHUSDC ▼]  账户: [Binance-1 ▼] │
│ 方向: (○) Neutral (○) Long (○) Short    │
│ 模式: (○) 等比 Geometric (○) 等差 Arith  │
├─────────────────────────────────────────┤
│ Lower Price [ 2400.00 ]                 │
│ Upper Price [ 2600.00 ]                 │
│ Grid Count  [ 20     ]  ← 拖动条 2-100  │
│                                         │
│ 投入金额    [ 1000    ] USDC            │
│ 杠杆        [ 3       ] x               │
├─────────────────────────────────────────┤
│ 📊 参数预览                              │
│  step_pct:            0.804%            │
│  每格数量 qty:        0.06 ETH          │
│  每格 notional:       150 USDC          │
│  每格净利润:          0.60% (扣手续费)   │
│  总名义金额:          3000 USDC         │
│  需要保证金:          1000 USDC ✓       │
│  区间宽度:            8.33%             │
│                                         │
│ ⚠️  提示：区间较窄，趋势市场易穿越        │
│ ✓  每格 notional 150 USDC 满足最小 5    │
├─────────────────────────────────────────┤
│ 切换 → 步长驱动模式                       │
└─────────────────────────────────────────┘
```

**关键交互**：
- 任意字段改动 → 300ms debounce → 前端立即重算预览
- 预览字段灰色只读，红字/黄字带图标
- 底部有"切换步长驱动模式"链接，切换时保留数值

### 13.6 后端 API 支持

除了 CRUD，加一个**预览接口**（不落库）：

```
POST /admin/mm/grid-v2/preview
Body:
  {
    "inputMode": "INVESTMENT_DRIVEN",  // or "STEP_DRIVEN"
    "symbol": "ETHUSDC",
    "lowerPrice": 2400,
    "upperPrice": 2600,
    "gridCount": 20,              // 模式 A 必填
    "investment": 1000,            // 模式 A 必填
    "stepPct": 0.5,               // 模式 B 必填
    "qtyPerGrid": 0.05,           // 模式 B 必填
    "leverage": 3,
    "gridMode": "GEOMETRIC"        // or "ARITHMETIC"
  }

Response:
  {
    "computed": {
      "stepPct": 0.804,
      "perGridQty": 0.06,
      "perGridNotional": 150,
      "perGridProfitPct": 0.60,
      "totalNotional": 3000,
      "requiredInvestment": 1000,
      "gridCount": 20
    },
    "warnings": [
      { "level": "warn", "msg": "区间较窄..." }
    ],
    "errors": []
  }
```

**好处**：
- 校验规则集中在后端，前端只负责渲染
- Symbol filters（tick/step/minQty/minNotional）已在后端缓存
- 未来加 API/CLI 时复用

### 13.7 存储决策

| 字段 | 存 DB | 说明 |
|---|---|---|
| `input_mode` | ✓ | 恢复表单时展示对应 tab |
| `lower_price` / `upper_price` | ✓ | 网格核心 |
| `grid_count` | ✓ | 网格核心 |
| `investment` | ✓ | 用户初始意图 |
| `leverage` | ✓ | |
| `grid_mode` | ✓ | ARITH/GEOM |
| `qty_per_grid` | ✓ | 最终生效数量（A 模式算出来存，B 模式用户填存）|
| `step_pct` | ✗ | 派生量，需要时从 lower/upper/grid_count 反算 |
| `per_grid_notional` / `per_grid_profit_pct` | ✗ | 派生量，展示时算 |

**为什么不存 step**：如果同时存 grid_count 和 step_pct，两者可能冲突（用户改一个忘改另一个）。**单一真理来源**：网格几何由 (lower, upper, grid_count, grid_mode) 唯一决定，step 是派生。

### 13.8 迁移到运行时

用户点"启动"：
```
1. 从 DB 读 (lower, upper, grid_count, grid_mode, qty_per_grid, direction, leverage, ...)
2. GridBuilder.build()：
     a. 计算每档价格 → tick 对齐
     b. 对齐后再算 per_grid_qty → step 对齐（若和 DB 存的不同则 log warn）
     c. 找到 mark 附近的 grid_index k
     d. 生成 slot 表 + intents
3. 校验 per_grid_qty * avg_price ≥ minNotional
4. persistRunning + 挂单
```

**运行时 slot 价格**（网格拓扑固定，一次算好后不变）：
```
etherium 等比: slot_i.price = lower × ratio^i, ratio = (upper/lower)^(1/grid_count)
etherium 等差: slot_i.price = lower + i × (upper-lower)/grid_count
```

**方案 α 里 V2 不需要"CLOSE 单价"** —— slot 拓扑固定，相邻挡自然构成配对。原来"CLOSE @ fill_price × (1+step)" 的语义在 V2 里等同于"相邻 slot 的 price"。

**step_multiplier X 只在手动 shiftWindow(recalc_step_with_x=true) 触发时改变有效 step**（见 §16 路径 α1，Trailing 是纯平移不走此路径）：
```
new_step_pct        = base_step_pct × (1 + X)              # X 放大 step
new_lower / new_upper 保持不变
new_grid_count      = ceil(ln(upper/lower) / ln(1 + new_step_pct/100))   # 等比重算档数
```
相当于用新的 X 全网格重建（α1）。大多数场景 X=0 不触发此路径，运行期 X 只能通过 α2「SPARSE 稀疏挂单」生效。

---

## 十四、Dynamic Order Mechanism（活跃窗口）

### 14.1 概念

**总格数（grid_count）** 和 **活跃订单数（active_window_size）** 解耦：

- **总格数**：网格逻辑拓扑的完整挡位数（例 200 或 1000）
- **活跃窗口**：实际挂在币安上的订单数（例 ±10 = 20 单），只覆盖 mark price 附近

窗口外的挡位在 `grid_slot_v2` 表里 status=OFF，只是元数据，**不占币安挂单配额**。

**为什么需要**：
- 币安单 symbol 挂单上限 200 单/账户，dynamic 可支持 1000 档策略
- 同账户多策略共存（10 个策略 × 20 单 = 200 单，全用满 quota）
- 降低 rate limit 压力 + 撤挂频率
- 内存操作 slot 状态 20 vs 200 差 10x

### 14.2 触发时机

**双触发保证跟得上**：

**a) 定时兜底（30s SlidingActiveWindowScheduler）**
- 检查所有 RUNNING 策略
- 若 mark 已离开当前窗口中心超过 `window_slide_pct` → 重算窗口
- 高波动可自适应缩短到 5s

**b) 成交事件驱动（immediate on fill）**
- 每次 fill 事件后，若新的 mark 距离活跃窗口边缘 < `edge_buffer` 挡 → 立即滑动
- 延迟从 30s → 毫秒级
- 避免边缘阴影区累积漏 fill

### 14.3 SlidingActiveWindowScheduler 伪代码

```java
void slideActiveWindow(GridStrategyV2 s, BigDecimal mark) {
    // 1. 找 mark 对应的 slot index
    int k = findSlotIndexNearest(s, mark);

    int windowSize = s.getActiveWindowSize();
    int bufferSlots = s.getActiveWindowBufferSlots();   // 默认 3

    // 2. 目标活跃范围（含 buffer）
    int activeStart = max(0, k - windowSize/2 - bufferSlots);
    int activeEnd   = min(s.getGridCount(), k + windowSize/2 + bufferSlots + 1);

    // 3. 撤单窗口比挂单窗口稍宽（避免边缘抖动频繁撤挂）
    int cancelStart = max(0, k - windowSize/2 - bufferSlots * 2);
    int cancelEnd   = min(s.getGridCount(), k + windowSize/2 + bufferSlots * 2 + 1);

    List<Intent> intents = new ArrayList<>();
    for (GridSlotV2 slot : slotsOf(s)) {
        boolean shouldBeActive = slot.idx >= activeStart && slot.idx < activeEnd;
        boolean shouldCancel   = slot.idx < cancelStart || slot.idx >= cancelEnd;
        boolean hasOrder       = slot.currentOrderId != null;

        if (shouldCancel && hasOrder) {
            // 太远 → 撤
            intents.add(CANCEL slot.currentOrderId);
            slot.setStatus("OFF");
            slot.setCurrentOrderId(null);
        } else if (shouldBeActive && !hasOrder && slot.idx != k) {
            // 应挂但没挂 —— 按 (direction, slot.idx vs k, has_position) 派生该挂什么
            String targetSide = computeDesiredSide(s.direction, slot.idx, k, slot.hasPosition);
            //   Neutral:  idx < k → BUY;  idx > k → SELL
            //   Long:     idx < k → BUY;  idx > k → (hasPosition ? SELL : OFF)
            //   Short:    idx > k → SELL; idx < k → (hasPosition ? BUY  : OFF)
            if ("OFF".equals(targetSide)) continue;

            String positionSide = (s.direction.equals("SHORT")) ? "SHORT" : "LONG";
            intents.add(PLACE targetSide + positionSide + " @ slot.price qty=perGridQty);
            slot.setStatus(targetSide);
        }
    }
    executor.execute(intents);
}
```

### 14.4 参数字段

新增到 `grid_strategy_v2` 表：

```sql
active_window_size          INT     DEFAULT NULL     -- 一侧挡数；NULL=不启用(全挂)
active_window_buffer_slots  INT     DEFAULT 3        -- 边缘 buffer（抗抖动）
window_slide_min_move_pct   DECIMAL(4,2) DEFAULT 0.5 -- mark 移动超过此% 才滑动
adaptive_tick_enabled       TINYINT DEFAULT 0        -- 高波动时缩短 tick 到 5s
fill_driven_slide_enabled   TINYINT DEFAULT 1        -- fill 后立即评估滑动（推荐开）
```

**默认行为**：
- `active_window_size = NULL` → 不启用 dynamic，一次全挂（`grid_count ≤ 20` 时推荐）
- 用户填 10 → 活跃 ±10 挡 + 3 buffer = 实际挂 ±13 挡（约 26 单）

### 14.5 数值示例

**配置**：ETHUSDC, range [2000, 3000], grid_count=200, active_window_size=10, buffer=3

**每挡精确 step**（等比）：
```
ratio    = (3000/2000)^(1/200) = 1.5^0.005 ≈ 1.002028
step_pct ≈ 0.2028%
每挡差价（在 2500 附近）≈ 2500 × 0.002028 ≈ $5.07
窗口 ±10 挡 ≈ ±$50.7
```

初始 mark=2500 (slot idx=100):
```
活跃窗口 = slot [90, 110]  (含 buffer 实际 [87, 113])
  slot 87..99  = BUY  (13 单)
  slot 100     = OFF  (中枢)
  slot 101..113 = SELL (13 单)
  slot 0..86   = OFF  (元数据在 DB, 无真单)
  slot 114..199 = OFF  

币安挂单实际 = 26 单，占 200 上限的 13%
DB 里 slot 表 = 200 行元数据
```

mark 涨到 2530 (slot 106):
```
新活跃窗口 = slot [96, 116]  (含 buffer [93, 119])
差异操作:
  撤: slot 87..92 (6 个 BUY) —— 现在离 mark 太远
  挂: slot 114..119 (6 个 SELL) —— 现在进入活跃范围
  中枢从 100 → 106 (Neutral 派生：k=106 时 slot 100 < k，按公式应挂 BUY，
                   由活跃窗口滑动补挂，非 fill 翻转产物)
执行:  6 cancel + 6 place = 12 API 请求
```

### 14.6 权衡说明（正常市场无损，极端场景加保险）

**这一节是要点** —— 之前担心的"跳空漏 fill"其实**只在极端场景才严重**。

**"missed fill" 只发生在"窗口内应该挂但没挂"，不是"窗口外没挂"**。窗口外没参与 → 没损失。

#### 场景分类

| 市场速度（每秒） | 30s 内累计 | 窗口 ±10 挡覆盖？ | 结果 |
|---|---|---|---|
| 正常 (<1 挡/s) | <30 挡 | 完全够 | ✓ 无损 |
| 快速 (1-5 挡/s) | 30-150 挡 | 边缘可能漏 1-2 挡 | 大部分 fill，损失小 |
| 极端 (>10 挡/s) | >300 挡 | 明显超窗口 | 漏 arb 机会（非漏 fill 本身）|
| 跳空 (0 时间穿越) | ∞ | 全穿 | 漏 arb 机会（币安 dynamic 也漏）|

**ETHUSDC 参考**（用上面的精确 step）：
- 每挡 step ≈ 0.2% ≈ $5（range=2000~3000, count=200）
- 窗口 10 挡 ≈ $50
- 正常价格 velocity $0.1~1/s → 30s 累计 $3~30，够窗口
- 快速趋势 $1~3/s → 30s 累计 $30~90，可能略超窗口
- 极端场景（新闻/清算/宕机）：$10~50/s，30s $300~1500 → 会大幅超窗口

**结论**：**日常市场用户直觉是对的 —— dynamic 不会漏 fill**。极端场景任何设计都会漏，只能靠 Stop Trigger 兜底。

#### 3 层保险（可组合）

**保险 1：fill 事件驱动即时滑动**
```
成交事件后立即检查：
    if mark 距离活跃窗口边缘 < edge_alert_slots (默认 3):
        立即触发 slideActiveWindow
```
延迟从 30s tick → 毫秒。**推荐默认打开**。

**保险 2：buffer 挡（14.3 已含）**
```
挂单范围 = active_window ± buffer_slots
撤单范围 = 挂单范围 × 2
```
边缘抖动不撤挂，快速移动时 buffer 挡先接盘。**默认 buffer=3**。

**保险 3：波动率自适应 tick**
```
if ATR_1min > ATR_baseline × 2:
    tick 频率从 30s → 5s
```
**默认关，用户可 opt-in**。

#### 决策矩阵

| 场景 | 无保险 | +fill 驱动 | +buffer=3 | +自适应 tick |
|---|---|---|---|---|
| 正常市场 | ✓ | ✓ | ✓ | ✓（无必要）|
| 快速趋势 | 损 1-2 挡 | ✓ | ✓ | ✓ |
| 极端行情 | 损整窗 | 损整窗 | 损部分 | 损部分 |
| 跳空 | 损 N 挡 | 同 | 损 N-buffer 挡 | 同 |

**推荐组合**：`active_window_size=用户值 + fill_driven=ON + buffer=3`。这三个默认开就够 99% 场景。

### 14.7 grid slot 翻转与活跃窗口的关系（原"CLOSE 单处理"）

**V2 方案 α 里没有独立 CLOSE 单**（见 §2.3）。所有单都是 slot 翻转产物，同样受活跃窗口约束。

**Slot 翻转 vs 活跃窗口的规则**：
- `slot_j` fill → 翻转后需要挂到 `slot_{j+1}`（或 `j-1`）
- 若 `slot_{j+1}` 在活跃窗口内 → 立即挂（正常 grid update）
- 若 `slot_{j+1}` 超出活跃窗口 → 不挂（等 SlidingActiveWindowScheduler 滑动窗口后自然覆盖）
  - 这不算漏 fill，只是延迟"预挂对手方"

**MARKET 单例外**（emergency 强平）：`role=MARKET`，不关联任何 slot，不受活跃窗口限制，直接下市价平当前仓位。

**账户挂单总上限（200 / symbol）**：如果多策略共用一个 symbol，活跃窗口总和 + 未成交历史挂单 > 180 时告警，>195 拒挂新 slot。检查由 `guard/AccountOrderQuotaGuard` 承担（§6.3）。

### 14.8 恢复逻辑（rehydrate）

JVM 重启：
```
GridRecoveryV2.recoverLive(strategy):
  1. 从 grid_slot_v2 表 rehydrate 全部 slot（元数据）
  2. 从 grid_order_v2 LIVE 记录反查 slot.currentOrderId
  3. 拉 positionRisk + openOrders 对账（复用 V1 逻辑）
  4. 计算当前 mark 对应的 slot idx k
  5. 立即调 slideActiveWindow(strategy, mark)
     ├─ 币安上仍在的 mm2-* 单：更新 slot.currentOrderId ✓
     ├─ 币安上不在但 DB 说 LIVE：标 UNKNOWN_RECONCILE
     └─ 活跃窗口内缺的：补挂
```

**关键**：恢复不需要"重挂 200 单"，只挂当前活跃 20 单，快 10 倍。

### 14.9 与 Binance 的对齐 + UX

**Binance 官方设计**：
> grid_count > 169 → 自动启用 Dynamic Order Mechanism，只挂 mark 附近 169 单

**V2 优化**：改为**用户显式配置**而不是隐式触发：
```
active_window_size:
  NULL → 全挂（grid_count ≤ 20 时推荐）
  20   → 只挂 ±20 挡（推荐 grid_count 50-1000 时用）
```

理由：
- 用户可预测（币安隐式触发让人困惑）
- 小网格也能强制 dynamic（多策略共账户时省 quota）

**UI 加一栏**（在 §13.5 的表单里）：
```
高级：
  □ 启用活跃窗口（Dynamic）
    活跃挡数（一侧）:  [ 10  ]
    Buffer 挡:        [ 3   ]
    Fill 触发即时滑动:  ☑ (推荐)
    Adaptive tick:    □ (高波动时缩短到 5s)
    
    ⓘ 网格 > 50 时建议启用
    ⓘ 币安账户挂单上限 200/symbol，多策略共享
    ⓘ 极端行情（Flash crash）可能漏 arb 机会，建议配 Stop Trigger 兜底
```

### 14.9.1 clientOrderId 命名规则（跨 V1/V2 共存关键）

同账户下 V1 和 V2 策略共享 UserDataStream。事件路由靠 `clientOrderId` 前缀区分：

| 版本 | 前缀 | 举例 |
|---|---|---|
| V1 网格 | `mm-<strategy_id>-<uuid8>` | `mm-2095580506609565698-1a1888d9` |
| V2 网格 | `mm2-<strategy_id>-<uuid8>` | `mm2-2095580506609565699-a3f27e1c` |
| V2 emergency 市价 | `mm2-emerg-<strategy_id>-<uuid8>` | `mm2-emerg-2095...-b4c918d2` |
| V2 trailing shift 撤/挂 | `mm2-trail-<strategy_id>-<uuid8>` | `mm2-trail-2095...-e5f2a301` |

**GridEngineV2 只处理 `mm2-` 前缀事件**，`mm-` 前缀留给 V1。反之亦然。避免跨版本误处理。

### 14.10 与 Stop Trigger 的关系

Dynamic 是**性能优化**，不是**风控**。跳空 / flash crash 场景下损失的是"arb 机会"，不是"资金安全"。

**资金安全靠 §四 的 Stop Trigger + max_position_qty 兜底**：
- 价格穿 upper/lower → 停加仓（位置止损）
- ROI% 或 PNL 到阈值 → 平仓（金额止损）
- 单侧仓位到 max → 停挂新单（数量止损）

Dynamic + Stop Trigger 组合 = 高效 + 安全。

---

## 十五、P&L 归因算法（V2 无独立 CLOSE 单的挑战）

> 原编号 §16，为逻辑连贯改为 §15。原 §15「Step Multiplier」下移为 §16。

### 15.1 问题

V2 方案 α 中没有独立的 CLOSE 单概念，OrderPairMatcher.onCloseFilled FIFO 配对逻辑不再适用。**如何算 arb_pnl / directional_pnl**？

### 15.2 算法：加权平均成本（Weighted Avg Cost，含手续费）

标准的期货持仓会计。每个 side 维护一个 `avg_cost`。

**关键：avg_cost 必须包含手续费成本**，否则 arb 策略的 P&L 会系统性高估（maker 返佣 -0.005% × 双向 = -0.01%，taker 手续费 +0.05%）。

每次 fill 事件带 `commission`（手续费）+ `commissionAsset`（USDC/BNB）。V2 将 commission 统一折算为 USDC 后并入价格：
```
effective_price_buy  = fill_price + (commission_usdc / fill_qty)   # 买入含 fee，成本上浮
effective_price_sell = fill_price - (commission_usdc / fill_qty)   # 卖出扣 fee，收入下降
（maker 返佣时 commission 为负，effective_price 反向调整）
```

```
Neutral 模式:
  LONG side (由 BUY fill 累积，由 SELL fill 减少):
    on BUY fill (qty=q, price=p, commission=c):
      p_eff = p + c/q          # 含 fee 有效成本
      new_avg = (avg_cost × pos + p_eff × q) / (pos + q)
      pos += q
      avg_cost = new_avg
    on SELL fill (qty=q, price=p, commission=c):
      p_eff = p - c/q          # 含 fee 有效收入
      if pos >= q (纯平仓):
        realized_pnl += (p_eff - avg_cost) × q
        pos -= q
        # avg_cost 不变（剩余仓位成本不变）
      elif pos > 0 (部分平仓 + 反向开仓):
        realized_pnl += (p - avg_cost) × pos    # 先平掉现有 LONG
        remaining_short = q - pos
        pos = 0; avg_cost = 0
        # remaining_short 开 SHORT side
        转到 SHORT side 逻辑，avg_cost_short = p, pos_short = remaining_short
      else (pos == 0, 纯开 SHORT):
        SHORT side: pos_short += q, avg_cost_short 加权平均
```

SHORT side 对称（SELL 增，BUY 减；p_eff 符号方向对称）。

**fee_paid 独立累加**（用于 UI 展示，与 realized_pnl 已含 fee 不重复）：
```
fee_paid += commission_usdc  # 每次 fill 都累加（返佣时为负）
```
UI 上：`realized_pnl（已含 fee）+ unrealized_pnl + funding_pnl`；`fee_paid` 只作为参考展示。

### 15.2.1 ROI 定义（消除歧义）

**统一定义**（Stop Trigger 和 UI 都用此公式）：
```
ROI% = (realized_pnl + unrealized_pnl + funding_pnl) / investment × 100
     = (含fee的净盈亏) / 初始投入 × 100
```
- realized_pnl 已含 fee（§15.2）
- funding_pnl 是资金费净额（正=收入，负=支出）
- 分母是 `investment`（初始 USDC），**不复利**（避免波动放大止损触发）

### 15.3 arb 和 directional 分离

**V2 里 arb 和 directional 的定义**（币安式，非 V1 的 FIFO）：
```
arb_realized_pnl:
  每次"回程"fill（LONG BUY 后 SELL 平，或 SHORT SELL 后 BUY 平）产生的 realized_pnl
  即上面算法里 realized_pnl 的累加

directional_realized_pnl:
  只在"多空转向"时才产生（部分平仓 + 反向开仓的过程）
  一般 Neutral 网格里为 0（正常震荡不发生转向）
  出现说明市场趋势大，穿过整个 LONG 消化完后开始累 SHORT
```

**总 realized_pnl = arb_realized_pnl + directional_realized_pnl**（后者常为 0）。

### 15.4 unrealized_pnl（浮动 P&L）

```
unrealized_pnl = pos × (mark_price - avg_cost)         # LONG side
              + pos_short × (avg_cost_short - mark_price)  # SHORT side
```

### 15.5 与 slot 翻转的对应关系

**每次 slot 翻转 = 1 个 fill 事件 = 上面算法的 1 步**：
```
slot_j (BUY status) fill @ P_j, qty = qty_per_grid:
  → LONG side 逻辑处理（因 direction=LONG 或 Neutral）
  → 更新 avg_cost / pos / realized_pnl
  → slot_j.status = OFF
  → slot_{j+1} 若原为 OFF → 变 SELL 挂上（挂在 P_{j+1}）
```

**arb 完成时机**：
```
最近一次 fill 是 slot_j SELL fill (@ P_j):
  → LONG side pos 减少 qty
  → realized += (P_j - avg_cost) × qty
  → 若 avg_cost 是之前 slot_{j-1} BUY fill (@ P_{j-1}) 的价:
      realized ≈ (P_j - P_{j-1}) × qty = step × avg_price × qty
      = 一次干净的 arb round-trip
```

### 15.6 落地组件

`pnl/` sub-package：
- `PositionAccountant`（新）：接收 `FillEvent`，用上面算法更新 `avg_cost / pos / realized_pnl`；内存权威 + 1min sync DB
- `PnlSnapshotService`（沿用）：从 `PositionAccountant` 读快照 + `mark_price` 算 unrealized
- `FundingIncomeService`（沿用）：LIVE 拉资金费入 realized（一般为负，付出方）

**移除**：`OrderPairMatcher`（V1 的 FIFO 配对逻辑，V2 不再需要）。

### 15.7 与 V1 数据的对比

| 指标 | V1 计算 | V2 计算 |
|---|---|---|
| 总 realized_pnl | Σ pair fill 差价 × qty | 同（用不同算法但等价）|
| arb_pnl | FIFO pair 内的 (close-open)×qty | 每次减仓 fill 的 (fill_price - avg_cost)×qty |
| dir_pnl | 未 pair 的 open 的浮盈亏 | 只在多空转向时非零 |
| 一致性 | 数字一致 | 数字近似一致（avg_cost 会平滑波动）|

**V2 优势**：不依赖 FIFO 配对，天然并发安全，恢复时从 grid_order 累加即可，不需要 pair_order_id 关联。

---

## 十六、Step Multiplier 与网格重挂决策（方案 α 下的新语义）

### 16.1 问题背景

§三 提到 V2 保留"打分驱动的动态步长" —— 趋势打分给出 `X ∈ [0, maxX]`。

**方案 α 关键约束**：V2 里没有独立 CLOSE 单，网格拓扑（`slot_i.price`）在 `GridBuilder.build()` 时一次算好，**运行期不变**。因此 X 变化**天然无处生效** —— 除非重建整个 slot 拓扑（改 price 数组或 grid_count）。

这与 V1 完全不同：
- V1：X 影响下次挂 CLOSE 的价格（`CLOSE @ fill × (1 + base_step × (1+X))`）→ 每次 fill 都能生效
- V2：CLOSE 就是相邻 slot 的固定价 → X 对已存在的 slot 无影响

### 16.2 V2 下 X 的两种真实生效路径

**路径 α1：手动 shiftWindow 触发时重建拓扑（Trailing 不走此路径，见 §2.6 纯平移语义）**
```
用户点"手动重挂"（可选 recalc_step_with_x=true）→ 撤所有活跃 slot 单
→ 若 recalc_step_with_x=true，用 X_current 重算 base_step（step_pct 是百分数，如 0.5 表示 0.5%）:
     new_step_pct = base_step_pct × (1 + X_current)
     new_grid_count = ceil(ln(upper/lower) / ln(1 + new_step_pct / 100))
   否则用当前 base_step_pct（等同"刷新窗口"）
→ 重建 grid_slot_v2 表所有行
→ **has_position 反推**（Long/Short 才需要）：同 §2.6 步骤 4 的 a-f
→ 按新拓扑挂活跃窗口内的 slot（含 has_position=1 的平仓 slot）
→ shift_window_count += 1, last_rebuild_at = now()
→ 发 GridRebuiltEvent
```
这是 X 唯一能改变几何的时机。**低频**（用户手动）。由 `lifecycle/ShiftWindowExecutor` 承担。

**路径 α2：SlidingActiveWindow 滑动时"跳挡"**
```
一种弱形式：X 大时，SlidingActiveWindowService 挂新 slot 时"跳档"
  X = 0    → 挂所有 active 窗口内 slot
  X = 0.5  → 每 1.5 挡挂一个（隔挡挂）
  X = 1.0  → 每 2 挡挂一个
效果：加密网格但降低实际挂单密度 = 等效放大 step
```
这个方案**不动 slot 拓扑**（DB 里所有 slot 仍在），只在挂单时按 X 稀疏化。恢复简单、无 queue 重置成本。**推荐 V2 采用此做法。**

### 16.3 方案对比

| 方案 | 生效时机 | 开销 | Queue 重置 | 归因影响 | 说明 |
|---|---|---|---|---|---|
| **α0：不生效**（默认） | 无 | 0 | 无 | 无 | X 只用于报表/告警，不影响挂单 |
| **α1：拓扑重建** | 手动 shiftWindow | 高（撤 N 挂 N）| 全部 | 无（Position Accountant 不关心 order） | 低频，用户可控（Trailing 走独立纯平移路径，不走 α1）|
| **α2：稀疏挂单** | SlidingActiveWindow tick | 微（少挂几单）| 无 | 无 | 推荐 |

**V1 的方案 A/B/C/D 讨论在 α 里失效**：
- 方案 A（不撤单）→ 等同 α0，X 无处生效
- 方案 B/C/D（撤重挂）→ 撤后重挂到**同价**（拓扑不变），没意义

### 16.4 API 开销与限额（背景参考）

**币安批量 API**：
- POST /fapi/v1/batchOrders：单批 5 单
- DELETE /fapi/v1/batchOrders：单批 10 orderId

**限额**：
- Weight limit：2400/min
- Order rate：300 orders/10s ≈ 1800 orders/min

**α1 拓扑重建**：假设活跃窗口 26 单，撤 26 + 挂 26 = 3 + 6 = 9 请求 / 52 order events，占限额 < 3%。**开销不是瓶颈**。

**α2 稀疏挂单**：只是"少挂几单"，等效降低了 SlidingActiveWindowService 的挂单频率，开销**更低**。

### 16.5 真实约束：queue position

即使 API 便宜，**撤单丢失队列优先级**才是核心成本：
```
BUY @2495 挂了 5min，在 book 队列中间
撤+重挂 → 新单进队尾
market 只吃到原位置就反弹 → 本可 fill，重挂后 fill 不了
```
maker-only 策略里 queue position 就是 edge，这是 α1 应低频触发的核心理由。

### 16.6 参数字段

```sql
step_multiplier_enabled     TINYINT DEFAULT 0        -- 是否开 X 计算
step_multiplier_max_x       DECIMAL(4,2) DEFAULT 0.5 -- X 上限
step_multiplier_mode        VARCHAR(16) DEFAULT 'REPORT_ONLY'
                            -- REPORT_ONLY (α0): X 只写入监控，不改挂单
                            -- SPARSE (α2):     SlidingActiveWindow 按 X 跳挡挂单
                            -- 拓扑重建 (α1) 只能通过 manual shiftWindow(recalc_step_with_x=true) 触发；Trailing 纯平移不走此路径
```

### 16.7 手动 shiftWindow API

```
POST /admin/mm/grid-v2/{id}/shiftWindow
Body: { "reason": "manual reset", "recalc_step_with_x": true }
```
用户判断市场结构变化时手动触发，走路径 α1，一次性重建 slot 拓扑。**低频高价值**操作，交给人判断。

### 16.8 决策落地

**V2 默认配置**：
- `step_multiplier_enabled = 0`（X 完全不算）
- 若开启 → `step_multiplier_mode = REPORT_ONLY`（只记录 X 到监控）
- 用户可显式改 `SPARSE` 启用 α2

**改配开启 X 的建议流程**：
1. 关闭 → 验证网格稳定运行
2. 开 `REPORT_ONLY`，观察 X 时间序列（Prometheus）
3. 判断是否值得 → 切 `SPARSE`
4. 极端市场结构变化 → 人工点手动 shiftWindow（α1）

### 16.9 与 V1 Adaptive 的差异总结

| 维度 | V1 AdaptiveStepCalculator | V2 Step Multiplier |
|---|---|---|
| 触发频率 | 5min 每次 | 5min 每次（但只算不撤挂）|
| 生效方式 | 撤挂全部单换新 step | REPORT_ONLY / SPARSE / 手动 α1 |
| Queue 影响 | 每次都重置 | 无（α0/α2）/ 仅手动时（α1） |
| P&L 归因风险 | 中（撤挂扰动 OrderPairMatcher）| 无（PositionAccountant 不依赖 order 状态） |
| 用户认知 | "看不见的 shift" | 用户明确知道 X 的效果 |

---

## 十七、边界与并发处理（实施必读）

前面章节假设了"理想 fill 事件"。实际生产要处理下列 7 类边界：

### 17.1 Partial Fill 处理

**问题**：Binance 期货支持部分成交，`ORDER_TRADE_UPDATE` 可能带 `executionType=TRADE` 但 `filled < order.origQty`。V2 slot 的 `has_position` 是布尔，无法表达"成交 60%"。

**方案**：
- **slot 状态推进以"完全成交"为准**（`executionType=TRADE && status=FILLED` 才算 slot 翻转）
- 部分成交时：更新 `grid_order_v2.filled_qty / avg_fill_price` + `PositionAccountant` 用实际 filled_qty 更新 pos/avg_cost；**slot.status 不变**、`has_position` 不动、**不补挂相邻 slot**
- 完全成交（`status=FILLED`）：走 §7.2 完整流程
- 撤单时若已 partial fill：交易所返回时带 `origQty` vs `executedQty`，剩余部分被撤；`slot.status = OFF` + 保留 `has_position` 反映实际持仓
- 边界：策略 stop 时未撤完的 partial fill 单，强制 MARKET 平余量

**PositionAccountant 影响**：每次 partial fill 都实时更新 avg_cost/pos，不等 slot 翻转。

### 17.2 GTX 拒单补救

**问题**：GTX (Good-Till-Crossing = Post-Only) 单若成为 taker 会被拒（错误码 -5022）。V2 slot 挂单失败时，该 slot 就永远缺挂。

**方案**：
- 拒单事件（`GtxRejectedEvent`）由 `execution/GridIntentExecutor` 发布
- 消费者 `grid/GridUpdateHandler`：
  1. slot.currentOrderId = null, status 保持不变（说明"应该挂但没挂上"）
  2. **不立即重试**（避免风暴）
  3. 等下一次 `SlidingActiveWindowService` tick（≤30s）自然补挂
  4. GTX 拒单计数 → `signal/DepletionSensor` 用于评估波动（连续拒单意味着 taker 侧强势）
- 高频连续拒单（如同 slot 5min 内 ≥3 次）→ 降级方案：等下一次 mark 明显反转再挂

**为什么不降级到 GTC**：GTC 单可能成 taker（吃手续费 0.05% vs maker 返佣 -0.005%），做市策略不能容忍。宁可等下一 tick。

### 17.3 事件总线并发模型

**决议**：Spring `ApplicationEventPublisher` 默认同步；V2 **强制所有 FillEvent listener 同步执行 + 显式排序**。

**FillEvent 消费顺序**（用 `@Order` 注解）：
```
@Order(1)  GridUpdateHandler         # 先翻转 slot 状态
@Order(2)  PositionAccountant        # 再更新 avg_cost/pos（依赖 slot 已翻转的 has_position）
@Order(3)  DepletionSensor           # 记 fill 事件
@Order(4)  WindowSlideListener       # 最后判断是否触发 slide（此时 slot 拓扑已一致）
```

**PriceMoveEvent 允许并发**（消费者互相独立）。

**跨事件锁**：同一 strategy_id 上的 FillEvent / SlidingActiveWindow / Trailing / shiftWindow 通过 `strategyStateLock` 串行化（`ConcurrentHashMap<Long, ReentrantLock>`），避免拓扑修改与 fill 处理竞态。

### 17.4 In-Flight Fill：Trailing / shiftWindow / EmergencyStop 中间态

**问题**：撤单期间（100-500ms）新 fill 到达，或撤单前 fill 已成交（返回 -2011 unknown order）。

**方案**：引入策略状态机
```
状态: RUNNING → REBUILDING → RUNNING
                → STOPPING → STOPPED
```

- `RUNNING`：正常处理 FillEvent
- `REBUILDING`（Trailing / α1 期间）：**FillEvent 入队暂存**，不改 slot 状态；rebuild 完成后重放 fill 到新拓扑（用 fill 的 price 找新 slot idx）
- `STOPPING`（EmergencyStop 期间）：**FillEvent 只更新 PositionAccountant（P&L 归因需要）**，不改 slot 状态、不补挂
- 撤单返回 -2011：查交易所 order status，若已 FILLED → 手动构造 FillEvent 补处理；若已 CANCELED → 忽略

**在 §5.1 加字段**：
```sql
status VARCHAR(24) DEFAULT 'STOPPED',  -- STOPPED / RUNNING / REBUILDING / STOPPING / EMERGENCY_STOPPED
```

### 17.5 clientOrderId 冲突预防

**问题**：`mm2-<strategy_id>-<uuid8>` 里 uuid8 = 8 位 hex，全生命周期挂 1M 单冲突概率 ≈ 1/4300。

**方案**：改为 `mm2-<strategy_id>-<epochMs36>-<uuid4>`，其中：
- `epochMs36` = 毫秒时间戳的 base36 编码（约 9 位）
- `uuid4` = UUID 前 4 位 hex
- 冲突需要"同一毫秒 + uuid4 撞车" → 概率 <1/65536，可接受

前缀路由仍按 `mm2-` 判断，长度增加 <15 字符（币安 clientOrderId 上限 36 字符仍安全）。

### 17.6 WS 断连超时兜底

**问题**：V1 UserStream 断连恢复复用，但 V2 slot 状态强依赖 fill 事件。断连>30s 期间可能丢 fill。

**方案**：
- `WsConnectionWatchdog`（V1 已有）监测 UserStream 断连时长
- 断连 > 30s → 发 `WsDisconnectionExtendedEvent`
- `GridRecoveryV2` 订阅该事件 → 触发**强制 has_position 反推**（同 §7.4 逻辑，不重启进程也做）
- 反推后强制 slide 一次补齐挂单

### 17.7 has_position 反推尾差处理

**问题**：反推得 `n = round(|net_qty| / qty_per_grid)`，可能有尾差（`n × qty_per_grid ≠ |net_qty|`）。

**方案**（按尾差大小分级）：
```
diff = |net_qty| - n × qty_per_grid
if |diff| < qty_per_grid × 0.1:  # <10% 一格
    忽略，只记 INFO 日志（正常手续费/精度累积）
elif |diff| < qty_per_grid × 0.5:  # <50% 一格
    警告日志，n 按四舍五入取值；PositionAccountant 直接用 positionRisk 净持仓覆盖内存 pos
else:  # 严重不一致
    ERROR 日志 + emergencyStop（人工介入）
```

**避免累积**：每次 α1 重建 / Trailing / Recovery 都做尾差检查，及早发现漂移。

---

## 附录 A：Binance 官方文档索引

- [What Is Futures Grid Trading? (FAQ)](https://www.binance.com/en/support/faq/what-is-futures-grid-trading-f4c453bab89648beb722aa26634120c3)
- [Trailing Up / Down FAQ](https://www.binance.com/en/support/faq/how-to-use-the-trailing-up-and-trailing-down-functions-in-usd%E2%93%A2-m-futures-grid-trading-7a7bb22420404385991dee3a0930207d)
- [Long/Short Grid Trading FAQ](https://binance.com/en/amp/support/faq/904e47602a3941b99e960a31e152a986)
- [Trading Rules of Futures Contracts](https://www.binance.com/en/futures/trading-rules)
- [Trailing Blog](https://www.binance.com/en/blog/futures/leveraging-market-momentum-with-trailing-up--trailing-down-features-in-usd%E2%93%A2m-futures-grid-trading-6830518331948603129)

---

*本文档为 V2 蓝图，实施前需要根据实盘观察调整参数默认值和 Phase 优先级。任何 V2 组件实现前请重新审视对应章节。*
