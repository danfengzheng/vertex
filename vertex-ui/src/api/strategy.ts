import { request } from '../utils/request';
import type { ApiResponse, PageResult, PageQuery } from '../types/api';
import type { KLineInterval } from './quote';

/**
 * 策略相关 API
 */

/** 指标类型 */
export type IndicatorType = 'MA' | 'EMA' | 'RSI' | 'MACD' | 'BOLL' | 'KDJ' | 'ATR' | 'VWAP' | 'STOCH_RSI' | 'WR' | 'SAR' | 'ADX' | 'SUPERTREND' | 'VOL_CONFIRM' | 'OBV';

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
};

/** 信号类型显示映射 */
export const SIGNAL_TYPE_LABELS: Record<SignalType, string> = {
  BUY: '买入',
  SELL: '卖出',
  NEUTRAL: '中性',
};

/** 指标配置 */
export interface StrategyIndicatorConfig {
  indicatorType: IndicatorType;
  params: Record<string, number>;
  weight: number;
  /** 指标专属K线周期（可选，为空时使用策略默认周期） */
  interval?: KLineInterval;
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
  enabled: number;
  /** 是否自动交易 0-否 1-是 */
  autoTrade: number;
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
