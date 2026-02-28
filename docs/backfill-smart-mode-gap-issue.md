# 手动历史补全无法补上中间缺口（如 04:00）— 原因说明

**现象**：在数据源管理中手动做历史补全，指定时间范围包含 04:00，但 04:00 这根 15m K 线仍然不会被同步进来。

**原因**：当前补全逻辑在「智能模式」下**只补区间两端的缺口**（首、尾），**不补区间内部的缺口**，所以中间的 04:00 永远不会被拉取。

---

## 一、手动补全的两种模式

`QuoteSourceController.backfillSingleInterval` 在用户指定了 `startTime` 和 `endTime` 时：

1. **Full 模式**：`continuousCount < 100` 或无数据时，对整个 `[startTime, endTime]` 做全量拉取并 `saveBatchFillingGapsOnly`，中间缺的 04:00 会被拉回来并写入。
2. **Smart 模式**：当区间内已有数据且「最长连续段」长度 ≥ 100 时，认为数据较连续，只做「两端」补全：
   - 只拉 **`[startTime, existingStart - 1]`**（比现有最早 bar 更早的部分）
   - 只拉 **`[existingEnd + 1, endTime]`**（比现有最晚 bar 更晚的部分）
   - **中间**（例如 03:45 和 04:15 之间的 04:00）**不会**再请求 REST，因此不会补上。

---

## 二、为何会进 Smart 模式且漏掉 04:00

- 你区间里已有不少 bar（03:45、04:15、04:30 … 到 05:30），只有中间缺 04:00。
- `countContinuous` 算的是「最长连续段」长度：04:15～05:30 这一段连续，条数很容易 ≥ 100，于是 `continuousCount >= 100`，进入 **Smart 模式**。
- Smart 模式里：
  - `existingStart` = 现有最早 openTime（例如 03:45）
  - `existingEnd` = 现有最晚 bar 的 closeTime（例如 05:30 的 closeTime）
  - 只补 `[startTime, existingStart - 1]` 和 `[existingEnd + 1, endTime]`，**04:00 落在 (existingStart, existingEnd) 中间，不会被补**。

所以：**只要区间内有一段 100+ 的连续 bar，就会走 Smart 模式；Smart 模式又只补首尾，导致中间缺口（如 04:00）即使用户手动补全也不会被同步。**

---

## 三、小结

| 项目 | 说明 |
|------|------|
| **现象** | 手动在数据源管理做历史补全，04:00 仍不进来 |
| **直接原因** | 进入 Smart 模式后，只补「首、尾」两段，不补「中间」缺口 |
| **条件** | 查询区间内已有数据，且最长连续段 ≥ 100 条 |
| **结果** | 04:00 落在中间缺口，永远不会被本次 backfill 请求到并写入 |

若要修掉这个行为，需要改 backfill 逻辑：在 Smart 模式（或所有带区间补全）下，**先识别区间内所有缺口**，对每个缺口子区间也调用 REST + `saveBatchFillingGapsOnly`，而不仅限于首尾两段。
