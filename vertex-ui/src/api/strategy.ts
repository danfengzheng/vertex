import { request } from '../utils/request';
import type { ApiResponse, PageResult, PageQuery } from '../types/api';
import type { KLineInterval } from './quote';

/**
 * 策略相关 API
 */

/** 指标类型 */
export type IndicatorType = 'MA' | 'EMA' | 'RSI' | 'MACD';

/** 信号类型 */
export type SignalType = 'BUY' | 'SELL' | 'NEUTRAL';

/** 指标类型显示映射 */
export const INDICATOR_TYPE_LABELS: Record<IndicatorType, string> = {
  MA: 'MA (移动平均线)',
  EMA: 'EMA (指数移动平均线)',
  RSI: 'RSI (相对强弱指标)',
  MACD: 'MACD',
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
  id: number;
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
  id: number;
  strategyId: number;
  strategyName: string;
  symbol: string;
  exchange: string;
  interval: KLineInterval;
  signalType: SignalType;
  signalStrength: number;
  price: string;
  signalTime: number;
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
  id: number;
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
  strategyId?: number;
  exchange?: string;
  symbol?: string;
  interval?: KLineInterval;
  signalType?: SignalType;
  startTime?: number;
  endTime?: number;
}

/** 策略管理 API */
export const strategyApi = {
  create: (data: StrategyCreateDTO): Promise<ApiResponse<number>> =>
    request.post('/strategy', data),

  update: (data: StrategyUpdateDTO): Promise<ApiResponse<void>> =>
    request.put('/strategy', data),

  delete: (id: number): Promise<ApiResponse<void>> =>
    request.delete(`/strategy/${id}`),

  getById: (id: number): Promise<ApiResponse<StrategyVO>> =>
    request.get(`/strategy/${id}`),

  page: (params: StrategyQueryDTO): Promise<ApiResponse<PageResult<StrategyVO>>> =>
    request.get('/strategy/page', { params }),

  enable: (id: number): Promise<ApiResponse<void>> =>
    request.post(`/strategy/${id}/enable`),

  disable: (id: number): Promise<ApiResponse<void>> =>
    request.post(`/strategy/${id}/disable`),
};

/** 信号管理 API */
export const signalApi = {
  page: (params: SignalQueryDTO): Promise<ApiResponse<PageResult<SignalVO>>> =>
    request.get('/signal/page', { params }),

  getById: (id: number): Promise<ApiResponse<SignalVO>> =>
    request.get(`/signal/${id}`),

  analyze: (strategyId: number): Promise<ApiResponse<void>> =>
    request.post('/signal/analyze', null, { params: { strategyId } }),
};
