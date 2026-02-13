import { request } from '../utils/request';
import type { ApiResponse, PageResult, PageQuery } from '../types/api';
import type { KLineInterval } from './quote';

/**
 * 策略相关 API
 */

/** 指标类型 */
export type IndicatorType = 'MA' | 'EMA' | 'RSI' | 'MACD' | 'BOLL' | 'KDJ' | 'ATR' | 'VWAP' | 'STOCH_RSI' | 'WR';

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
}

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

  /** 快速回测 — 使用最近 N 天数据对策略做完整回测 */
  quick: (strategyId: string, days?: number, initialCapital?: number): Promise<ApiResponse<BacktestResultVO>> =>
    request.post('/backtest/quick', null, {
      params: { strategyId, days: days ?? 30, initialCapital: initialCapital ?? 10000 },
    }),
};
