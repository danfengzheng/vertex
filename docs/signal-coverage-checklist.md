# 信号监控与回测信号覆盖 — 检查清单与测试建议

用于验证：**信号监控产生的信号能否完全覆盖回测产生的信号**（仅针对 BUY/SELL，不含 NEUTRAL 落库）。

---

## 一、配置检查清单

在跑回测或实盘前，确认以下项与“对齐回测”目标一致。

| # | 检查项 | 推荐值 | 说明 |
|---|--------|--------|------|
| 1 | `vertex.strategy.engine.onlyClosedKlines` | **true** | 必须为 true，否则实盘会用未收盘 K 线，与回测“收盘后评估”不一致。 |
| 2 | `vertex.strategy.engine.warmupMultiplier` | **3**（默认） | 与回测使用的预热倍数一致，不要单独改实盘或回测一侧。 |
| 3 | `vertex.strategy.engine.maxKlineHistory` | **500**（默认） | 回测预取时也受此限制，两处保持一致。 |
| 4 | 策略的指标配置 | 回测与实盘用**同一策略** | 同一 strategyId、同一 indicatorConfigs（指标类型、周期、参数、权重）。 |
| 5 | K 线数据源 | 回测与实盘用**同一 exchange、symbol、interval** | 数据来自同一 KLineStore（如 RocksDB），且回测时间区间内数据已完整回填。 |

若 1 为 false，则**不保证**信号监控能覆盖回测信号，建议仅在调试时使用。

---

## 二、数据一致性检查

保证“同一根 bar”在回测和信号监控里看到的数据相同。

| # | 检查项 | 操作建议 |
|---|--------|----------|
| 1 | 回测前回填 | 回测 `startTime` 之前至少有 `required × warmupMultiplier + 10` 根 K 线，避免回测起始段与实盘预热长度不一致。 |
| 2 | 时区/时间戳 | 确认 K 线 `openTime` 为同一时区（建议 UTC 或交易所时间一致），回测的 start/end 与实盘触发时间对齐。 |
| 3 | 缺失 bar | 若某周期有缺 bar，回测会跳过或使用不完整窗口，实盘同样会得到不同窗口，需先补全再对比。 |

---

## 三、验证方法（建议流程）

### 方法 A：历史区间“回放对比”（推荐）

1. **选一段已结束的历史区间** `[T_start, T_end]`，且该区间 K 线已完整写入 KLineStore。
2. **跑回测**：用同一策略、该区间，得到回测信号列表（只保留 BUY/SELL），按 `signalTime` 排序。
3. **不实际回放事件**时，可用“按时间逐 bar 调用策略引擎”的方式模拟：
   - 对区间内每一根**已收盘**的主周期 bar（openTime = T），调用策略引擎的评估逻辑（或手动触发 `runStrategy(strategyId)` 并传入 T 作为窗口上界，若当前代码支持传入时间）。
   - 若当前只有“事件驱动”的入口，可写一个**一次性脚本**：遍历区间内每根 bar 的 openTime，构造 KLineEvent（或直接调 StrategyEngineService 的评估，传入 endTime=T），收集产生的 BUY/SELL。
4. **对比**：
   - 对回测中每个 BUY/SELL 的 `(strategyId, signalTime, signalType)`，在实盘/回放结果中应存在相同三元组。
   - 实盘多出的 BUY/SELL（同一 strategyId、同一 signalTime）若回测没有，说明该 bar 回测与实盘窗口或数据不一致，需查数据与窗口。

**简化版（不写脚本）**：  
在历史区间内选几个“回测产生 BUY 或 SELL”的 `signalTime`，在信号监控/数据库中查该策略是否在该时间有同类型信号；并检查该时刻前后 K 线是否与回测使用的数据一致。

### 方法 B：同一策略、同一时间段“双跑”

1. 选一个**已启用**的策略，确保其 `indicatorConfigs`、主周期、多周期与回测一致。
2. 确保 `[T_start, T_end]` 的 K 线已在 Store 中，且回测在该区间已跑过并保存结果。
3. 通过**手动触发**或**事件回放**，在 T_start～T_end 内按 bar 顺序触发策略评估（仅已收盘 bar），记录每次的 BUY/SELL 及 `signalTime`。
4. 与回测结果逐条对比 `(signalTime, signalType)`，应一致。

### 方法 C：单 bar 单元级校验（调试用）

1. 取回测中一条 BUY 或 SELL，记下 `strategyId`、`signalTime`（即某根 bar 的 openTime）、`signalType`。
2. 从 KLineStore 拉取该策略在该 `signalTime` 的评估窗口（主周期 + 各副周期）：  
   - 主周期：以 signalTime 为右端、长度为 `min(required*warmup+10, maxKlineHistory)` 的升序 K 线；  
   - 副周期：openTime ≤ signalTime，取最后 `fetchSize` 根。
3. 用同一策略配置和上述窗口，本地调用 `SignalGenerator.evaluate(...)`，应得到相同 `signalType`。
4. 若不一致，检查：窗口是否与回测完全一致（长度、顺序、closed）、指标参数是否一致。

---

## 四、常见不一致原因速查

| 现象 | 可能原因 |
|------|----------|
| 回测某 bar 有 BUY，实盘该 bar 无信号或为 SELL/NEUTRAL | 实盘窗口缺 bar 或多了 bar；或 onlyClosedKlines=false 导致用了未收盘 bar；或该 bar 未触发事件（漏通知）。 |
| 同一 bar 回测 NEUTRAL，实盘未在库中看到 | 正常：实盘不落库 NEUTRAL，只对比 BUY/SELL 即可。 |
| 实盘多出回测没有的 BUY/SELL | 实盘用了更多/更新的数据（如多了一根 bar），或事件重复触发、幂等未生效。 |
| 多周期策略不一致 | 某副周期在回测与实盘中的“截止时间”或“最后 N 根”不一致，检查 endTime 与 fetchSize 是否一致。 |

---

## 五、当前项目默认配置摘要

- `vertex.strategy.engine.onlyClosedKlines` = **true**
- `vertex.strategy.engine.warmupMultiplier` = **3**
- `vertex.strategy.engine.maxKlineHistory` = **500**
- 回测与 StrategyEngineService 共用 **SignalGenerator.evaluate()**，窗口公式一致；KLineStore 查询 endTime 为**含**（包含 openTime=T 的 bar）。

在保持上述配置、且数据一致的前提下，按本清单做一次“历史区间回放对比”或“双跑对比”，即可验证信号监控是否能完全覆盖回测产生的 BUY/SELL 信号。
