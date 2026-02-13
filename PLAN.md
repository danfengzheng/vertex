# 新增高频短线交易指标

## 目标
为 Vertex 策略系统新增 3 个适合短期持有/高频交易的技术指标，并在前后端完成注册。

## 新增指标

### 1. VWAP (成交量加权平均价格)
- **用途**: 日内/短线交易核心指标，判断当前价格相对于成交量加权均价的偏离
- **参数**: 无（使用全部窗口K线计算）
- **信号逻辑**:
  - 价格 < VWAP × 0.998 → BUY (价格低于成交量加权均价，可能被低估)
  - 价格 > VWAP × 1.002 → SELL (价格高于成交量加权均价，可能被高估)
  - 否则 → NEUTRAL
- **返回值**: `{vwap: xxx, deviation: xxx}`

### 2. STOCH_RSI (随机RSI)
- **用途**: 比普通 RSI 更敏感，适合捕捉短期超买超卖反转
- **参数**: `rsiPeriod` (默认14), `stochPeriod` (默认14), `kSmooth` (默认3), `dSmooth` (默认3)
- **公式**: 先计算RSI序列，再对RSI序列做随机指标 (Stochastic) 处理
- **信号逻辑**:
  - K 线上穿 D 线 且 K < 20 → BUY (超卖区金叉)
  - K 线下穿 D 线 且 K > 80 → SELL (超买区死叉)
  - 否则 → NEUTRAL
- **返回值**: `{stochRsiK: xxx, stochRsiD: xxx}`

### 3. WR (威廉指标 / Williams %R)
- **用途**: 超灵敏的超买超卖指标，非常适合短线高频交易的快速进出
- **参数**: `period` (默认14)
- **公式**: %R = (最高价 - 收盘价) / (最高价 - 最低价) × (-100)
- **信号逻辑**:
  - WR < -80 → BUY (超卖)
  - WR > -20 → SELL (超买)
  - 否则 → NEUTRAL
- **返回值**: `{wr14: xxx}`

## 修改文件清单

### 后端 (Java)
1. **`model/.../IndicatorType.java`** — 枚举新增 `VWAP`, `STOCH_RSI`, `WR`
2. **`strategy-service/.../impl/VwapIndicator.java`** — 新建 VWAP 指标实现
3. **`strategy-service/.../impl/StochRsiIndicator.java`** — 新建 STOCH_RSI 指标实现
4. **`strategy-service/.../impl/WilliamsRIndicator.java`** — 新建 WR 指标实现

### 前端 (TypeScript/React)
5. **`vertex-ui/src/api/strategy.ts`** — `IndicatorType` 类型和 `INDICATOR_TYPE_LABELS` 新增 3 个
6. **`vertex-ui/src/pages/strategy/StrategyConfig.tsx`** — `IndicatorParamsFields` 新增参数表单
7. **`vertex-ui/src/i18n/locales/zh-CN.json`** — 新增国际化 key
8. **`vertex-ui/src/i18n/locales/en-US.json`** — 新增国际化 key
