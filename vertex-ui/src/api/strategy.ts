import { request } from '../utils/request';
import type { ApiResponse, PageResult, PageQuery } from '../types/api';
import type { KLineInterval } from './quote';

/**
 * 策略相关 API
 */

/** 指标类型 */
export type IndicatorType = 'MA' | 'EMA' | 'RSI' | 'MACD' | 'BOLL' | 'KDJ' | 'ATR' | 'VWAP' | 'STOCH_RSI' | 'WR' | 'SAR' | 'ADX' | 'SUPERTREND' | 'VOL_CONFIRM' | 'OBV' | 'DIVERGENCE';

/** 信号类型 */
export type SignalType = 'BUY' | 'SELL' | 'NEUTRAL';

/** 指标类型显示映射 */
export const INDICATOR_TYPE_LABELS: Record<IndicatorType, string> = {
  MA: 'MA (移动平均线)',
  EMA: 'EMA (指数移动平均线)',
  RSI: 'RSI (相对强弱指标)',
  MACD: 'MACD',
  BOLL: 'BOLL (布林带)',
  KDJ: 'KDJ (随机指标)',
  ATR: 'ATR (平均真实波幅)',
  VWAP: 'VWAP (成交量加权均价)',
  STOCH_RSI: 'StochRSI (随机RSI)',
  WR: 'WR (威廉指标)',
  SAR: 'SAR (抛物线转向)',
  ADX: 'ADX (平均趋向指数)',
  SUPERTREND: 'SuperTrend (超级趋势)',
  VOL_CONFIRM: 'VOL (成交量确认)',
  OBV: 'OBV (能量潮)',
  DIVERGENCE: '背离指标 (Divergence)',
};

/** 信号类型显示映射 */
export const SIGNAL_TYPE_LABELS: Record<SignalType, string> = {
  BUY: '买入',
  SELL: '卖出',
  NEUTRAL: '中性',
};

/** 指标配置 */
/** 硬性过滤器的数值条件 */
export interface FilterCondition {
  /** 指标计算值字段名，如 "volRatio"、"adx"、"rsi14"、"trend" */
  field: string;
  /** 运算符：GT(>) GTE(>=) LT(<) LTE(<=) EQ(=) */
  op: 'GT' | 'GTE' | 'LT' | 'LTE' | 'EQ';
  /** 阈值 */
  threshold: number;
  /**
   * 条件适用的信号方向：
   * - undefined/null：双向通用，复合投票前校验，任一失败则跳过所有复合计算
   * - "BUY"：仅对买入信号有效，复合投票后校验
   * - "SELL"：仅对卖出信号有效，复合投票后校验
   */
  applyTo?: 'BUY' | 'SELL';
}

export interface StrategyIndicatorConfig {
  indicatorType: IndicatorType;
  params: Record<string, number>;
  weight: number;
  penaltyWeight?: number;
  /** 指标专属K线周期（可选，为空时使用策略默认周期） */
  interval?: KLineInterval;
  /**
   * 是否为硬性过滤器（默认 false）
   * filterConditions 为空 → 方向校验（指标方向必须与复合信号一致）
   * filterConditions 非空 → 数值校验（校验指标计算值，忽略方向）
   */
  hardFilter?: boolean;
  filterConditions?: FilterCondition[];
}

/** 交易模式 */
export type TradeMode = 'AUTO' | 'MANUAL';

/** 执行模式 */
export type ExecutionMode = 'LIVE' | 'PAPER';

/** 仓位计算模式 */
export type PositionSizing = 'FIXED' | 'PERCENT';

/** 策略 VO */
export interface StrategyVO {
  id: string;
  name: string;
  description: string;
  exchange: string;
  symbol: string;
  interval: KLineInterval;
  indicatorConfigs: StrategyIndicatorConfig[];
  /** 出场指标配置（与入场独立，为空时跳过指标出场） */
  exitIndicatorConfigs?: StrategyIndicatorConfig[];
  /** 最大持仓K线根数（时间止损，null=不限） */
  maxHoldingBars?: number | null;
  enabled: number;
  /** 是否自动交易 0-否 1-是 */
  autoTrade: number;
  /** 自动交易最低信号强度门槛（0-100） */
  minSignalStrength?: number;
  /** 交易模式 */
  tradeMode?: TradeMode;
  /** 执行模式 */
  executionMode?: ExecutionMode;
  /** 交易所账户ID */
  accountId?: string;
  /** 仓位计算模式 */
  positionSizing?: PositionSizing;
  /** 每次交易数量（FIXED模式） */
  tradeQuantity?: number;
  /** 仓位比例 0-1（PERCENT模式） */
  positionRatio?: number;
  /** 模拟初始资金（PERCENT+PAPER模式） */
  initialCapital?: number;
  /** 止损百分比 */
  stopLossPct?: number;
  /** 止盈百分比 */
  takeProfitPct?: number;
  /** 手续费率（如 0.001 = 0.1%） */
  feeRate?: number;
  /** 合约杠杆倍数（1-125，仅合约账户有效） */
  leverage?: number;
  /** 合约保证金模式（ISOLATED/CROSS，仅合约账户有效） */
  marginType?: string;
  /** ATR止损倍数（如 2.0），设置后优先于固定止损百分比 */
  atrStopMultiplier?: number;
  /** ATR止盈倍数（如 3.0），设置后优先于固定止盈百分比 */
  atrTakeProfitMultiplier?: number;
  /** 移动止损：阶段1初始止损倍数（如 3.5） */
  initialStopMultiplier?: number;
  /** 移动止损：阶段2激活保本的ATR距离倍数（如 1.8） */
  breakevenActivationMultiplier?: number;
  /** 移动止损：阶段3激活追踪的ATR距离倍数（如 2.5） */
  trailingActivationMultiplier?: number;
  /** 移动止损：阶段4追踪距离倍数（如 2.0） */
  trailingDistanceMultiplier?: number;
  /** ATR止损专用K线周期（留空则使用策略默认周期） */
  atrInterval?: KLineInterval;
  /** 峰值回撤止损百分比（如 5.0 = 5%），从峰值回撤超过此值时止损 */
  trailingDropPct?: number;
  /** SuperTrend 动态止损偏移百分比（如 1.0 = 1%）。需同时配置 SUPERTREND 指标才生效 */
  superTrendSlOffsetPct?: number;
  /**
   * NEUTRAL 信号时的反向指标占比出场判据（0-1；null=不启用）。
   * 分母 = 总投票指标数（含 NEUTRAL），不算权重、不算 FILTER。
   * 例：4 个投票指标，配 0.25 → 出现 1 个反向就平；配 0.5 → 出现 2 个反向才平。
   */
  exitOnOppositeVoteRatio?: number | null;
  /** 止损熔断开关（1=启用），止损触发且亏损时暂停开仓 24 小时 */
  pauseOnStopLoss?: number;
  /** 交易暂停截止时间（UTC ISO字符串），非 null 表示当前处于止损熔断期 */
  tradingPausedUntil?: string | null;
  /** 分阶段止盈：第1档触发价百分比 */
  takeProfitPct1?: number;
  /** 分阶段止盈：第1档平仓比例（0-100）。>0 即启用，启用后单级止盈被忽略 */
  takeProfitSize1?: number;
  /** 分阶段止盈：第2档触发价百分比（可选） */
  takeProfitPct2?: number;
  /** 分阶段止盈：第2档平仓比例（可选） */
  takeProfitSize2?: number;
  /** 分阶段止盈：第3档触发价百分比（可选） */
  takeProfitPct3?: number;
  /** 分阶段止盈：第3档平仓比例（可选） */
  takeProfitSize3?: number;
  /** 触发指定档后将止损上移到入场价（保本退出）：1/2/3；0/null=不启用 */
  moveStopToBreakevenAfterStage?: number;
  createTime: string;
  updateTime: string;
}

/** 信号 VO */
export interface SignalVO {
  id: string;
  strategyId: string;
  strategyName: string;
  symbol: string;
  exchange: string;
  interval: KLineInterval;
  signalType: SignalType;
  signalStrength: number;
  price: string;
  signalTime: string | number;
  indicators: Record<string, number>;
  description: string;
  createTime: string;
}

/** 策略创建参数 */
export interface StrategyCreateDTO {
  name: string;
  description?: string;
  exchange: string;
  symbol: string;
  interval: KLineInterval;
  indicatorConfigs: StrategyIndicatorConfig[];
  /** 出场指标配置（可选，为空则仅使用止损止盈方式平仓） */
  exitIndicatorConfigs?: StrategyIndicatorConfig[];
  /** 最大持仓K线根数（时间止损，null=不限） */
  maxHoldingBars?: number | null;
  autoTrade?: number;
  tradeMode?: TradeMode;
  executionMode?: ExecutionMode;
  accountId?: string;
  positionSizing?: PositionSizing;
  tradeQuantity?: number;
  positionRatio?: number;
  initialCapital?: number;
  stopLossPct?: number;
  takeProfitPct?: number;
  feeRate?: number;
  leverage?: number;
  marginType?: string;
  /** ATR止损倍数（如 2.0），设置后优先于固定止损百分比 */
  atrStopMultiplier?: number;
  /** ATR止盈倍数（如 3.0），设置后优先于固定止盈百分比 */
  atrTakeProfitMultiplier?: number;
  initialStopMultiplier?: number;
  breakevenActivationMultiplier?: number;
  trailingActivationMultiplier?: number;
  trailingDistanceMultiplier?: number;
  atrInterval?: KLineInterval;
  /** 峰值回撤止损百分比（如 5.0 = 5%） */
  trailingDropPct?: number;
  /** SuperTrend 动态止损偏移百分比（如 1.0 = 1%） */
  superTrendSlOffsetPct?: number;
  /**
   * NEUTRAL 信号时的反向指标占比出场判据（0-1；null=不启用）。
   * 分母 = 总投票指标数（含 NEUTRAL），不算权重、不算 FILTER。
   */
  exitOnOppositeVoteRatio?: number | null;
  /** 止损熔断开关（true=启用），止损触发且亏损时暂停开仓 24 小时 */
  pauseOnStopLoss?: boolean;
  /** 分阶段止盈第1档 */
  takeProfitPct1?: number;
  takeProfitSize1?: number;
  /** 分阶段止盈第2档（可选） */
  takeProfitPct2?: number;
  takeProfitSize2?: number;
  /** 分阶段止盈第3档（可选） */
  takeProfitPct3?: number;
  takeProfitSize3?: number;
  /** 触发指定档后将止损上移到入场价：1/2/3；0/null=不启用 */
  moveStopToBreakevenAfterStage?: number;
}

/** 策略更新参数 */
export interface StrategyUpdateDTO {
  id: string;
  name?: string;
  description?: string;
  exchange?: string;
  symbol?: string;
  interval?: KLineInterval;
  indicatorConfigs?: StrategyIndicatorConfig[];
  /** 出场指标配置（传空列表=清除出场指标配置） */
  exitIndicatorConfigs?: StrategyIndicatorConfig[];
  /** 最大持仓K线根数（时间止损，null=不限） */
  maxHoldingBars?: number | null;
  autoTrade?: number;
  tradeMode?: TradeMode;
  executionMode?: ExecutionMode;
  accountId?: string;
  positionSizing?: PositionSizing;
  tradeQuantity?: number;
  positionRatio?: number;
  initialCapital?: number;
  stopLossPct?: number;
  takeProfitPct?: number;
  feeRate?: number;
  leverage?: number;
  marginType?: string;
  /** ATR止损倍数（如 2.0），设置后优先于固定止损百分比 */
  atrStopMultiplier?: number;
  /** ATR止盈倍数（如 3.0），设置后优先于固定止盈百分比 */
  atrTakeProfitMultiplier?: number;
  initialStopMultiplier?: number;
  breakevenActivationMultiplier?: number;
  trailingActivationMultiplier?: number;
  trailingDistanceMultiplier?: number;
  atrInterval?: KLineInterval;
  /** 峰值回撤止损百分比（如 5.0 = 5%） */
  trailingDropPct?: number;
  /** SuperTrend 动态止损偏移百分比（如 1.0 = 1%） */
  superTrendSlOffsetPct?: number;
  /**
   * NEUTRAL 信号时的反向指标占比出场判据（0-1；null=不启用）。
   * 分母 = 总投票指标数（含 NEUTRAL），不算权重、不算 FILTER。
   */
  exitOnOppositeVoteRatio?: number | null;
  /** 止损熔断开关（true=启用，false=关闭） */
  pauseOnStopLoss?: boolean;
  minSignalStrength?: number;
  /** 分阶段止盈第1档 */
  takeProfitPct1?: number;
  takeProfitSize1?: number;
  /** 分阶段止盈第2档（可选） */
  takeProfitPct2?: number;
  takeProfitSize2?: number;
  /** 分阶段止盈第3档（可选） */
  takeProfitPct3?: number;
  takeProfitSize3?: number;
  /** 触发指定档后将止损上移到入场价：1/2/3；0/null=不启用 */
  moveStopToBreakevenAfterStage?: number;
}

/** 策略查询参数 */
export interface StrategyQueryDTO extends PageQuery {
  name?: string;
  exchange?: string;
  symbol?: string;
  enabled?: number;
}

/** 信号查询参数 */
export interface SignalQueryDTO extends PageQuery {
  strategyId?: string;
  exchange?: string;
  symbol?: string;
  interval?: KLineInterval;
  signalType?: SignalType;
  startTime?: number;
  endTime?: number;
}

/** 回测配置 */
export interface BacktestConfigDTO {
  strategyId: string;
  startTime: number;
  endTime: number;
  initialCapital?: number;
  positionRatio?: number;
  feeRate?: number;
  /** 启用 AI 批量分析（异步执行） */
  enableAiAnalysis?: boolean;
  /** 强制重跑，忽略 RocksDB 缓存 */
  forceRefresh?: boolean;
}

/** 交易记录 */
export interface TradeRecord {
  entryTime: number;
  exitTime: number;
  type: string;
  entryPrice: string;
  exitPrice: string;
  quantity: string;
  profit: string;
  profitPercent: string;
  /** 平仓原因：SIGNAL | STOP_LOSS | TAKE_PROFIT | MAX_BARS | INDICATOR_EXIT | END_OF_BACKTEST */
  exitReason?: string;
}

/** 资金曲线数据点 */
export interface EquityPoint {
  time: number;
  equity: number;
}

/** 回测结果 */
export interface BacktestResultVO {
  strategyId: string;
  strategyName: string;
  startTime: number;
  endTime: number;
  initialCapital: string;
  finalCapital: string;
  totalProfit: string;
  returnRate: string;
  totalTrades: number;
  winningTrades: number;
  losingTrades: number;
  winRate: string;
  profitLossRatio: string;
  maxDrawdown: string;
  maxDrawdownDuration: number;
  sharpeRatio: string;
  trades: TradeRecord[];
  equityCurve: EquityPoint[];
  /** 回测缓存 key（SHA256 hex），用于查询 AI 分析与进度 */
  cacheKey?: string | null;
  /** 是否命中缓存 */
  cached?: boolean | null;
  /** 缓存创建时间戳（ms, UTC） */
  cachedAt?: number | null;
  /** AI 分析状态 */
  aiAnalysisStatus?: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED' | null;
}

/** 策略管理 API */
export const strategyApi = {
  create: (data: StrategyCreateDTO): Promise<ApiResponse<string>> =>
    request.post('/strategy', data),

  update: (data: StrategyUpdateDTO): Promise<ApiResponse<void>> =>
    request.put('/strategy', data),

  delete: (id: string): Promise<ApiResponse<void>> =>
    request.delete(`/strategy/${id}`),

  getById: (id: string): Promise<ApiResponse<StrategyVO>> =>
    request.get(`/strategy/${id}`),

  page: (params: StrategyQueryDTO): Promise<ApiResponse<PageResult<StrategyVO>>> =>
    request.get('/strategy/page', { params }),

  enable: (id: string): Promise<ApiResponse<void>> =>
    request.post(`/strategy/${id}/enable`),

  disable: (id: string): Promise<ApiResponse<void>> =>
    request.post(`/strategy/${id}/disable`),

  copy: (id: string): Promise<ApiResponse<string>> =>
    request.post(`/strategy/${id}/copy`),
};

/** 信号管理 API */
export const signalApi = {
  page: (params: SignalQueryDTO): Promise<ApiResponse<PageResult<SignalVO>>> =>
    request.get('/signal/page', { params }),

  getById: (id: string): Promise<ApiResponse<SignalVO>> =>
    request.get(`/signal/${id}`),

  analyze: (strategyId: string): Promise<ApiResponse<void>> =>
    request.post('/signal/analyze', null, { params: { strategyId } }),
};

/** 回测 API */
export const backtestApi = {
  run: (config: BacktestConfigDTO): Promise<ApiResponse<BacktestResultVO>> =>
    request.post('/backtest/run', config),

  /** 快速回测 — 使用最近 N 天数据对策略做完整回测，参数与策略配置回测保持一致 */
  quick: (params: {
    strategyId: string;
    days?: number;
    initialCapital?: number;
    positionRatio?: number;
    feeRate?: number;
  }): Promise<ApiResponse<BacktestResultVO>> =>
    request.post('/backtest/quick', null, {
      params: {
        strategyId: params.strategyId,
        days: params.days ?? 30,
        initialCapital: params.initialCapital ?? 10000,
        positionRatio: params.positionRatio ?? 1,
        feeRate: params.feeRate ?? 0.001,
      },
    }),
};
