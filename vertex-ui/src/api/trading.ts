import { request } from '../utils/request';
import type { ApiResponse, PageResult, PageQuery } from '../types/api';

/**
 * 交易模块 API
 */

// ─── 枚举类型 ─────────────────────────────────────

export type OrderSide = 'BUY' | 'SELL';
export type OrderType = 'MARKET' | 'LIMIT';
export type OrderStatus = 'PENDING' | 'SUBMITTED' | 'FILLED' | 'PARTIALLY_FILLED' | 'CANCELLED' | 'REJECTED' | 'EXPIRED' | 'SIMULATED';
export type TradeMode = 'AUTO' | 'MANUAL';
export type ExecutionMode = 'LIVE' | 'PAPER';
export type PositionSide = 'LONG' | 'SHORT';
export type PositionStatus = 'OPEN' | 'CLOSED';
export type MarketType = 'SPOT' | 'USDM' | 'COINM';
export type MarginType = 'ISOLATED' | 'CROSS';

// ─── 接口定义 ─────────────────────────────────────

/** 交易所账户 VO */
export interface ExchangeAccountVO {
  id: string;
  name: string;
  exchange: string;
  marketType: MarketType;
  status: number;
  createTime: string;
  updateTime: string;
}

/** 交易订单 VO */
export interface OrderVO {
  id: string;
  strategyId: string;
  strategyName?: string;
  accountId: string;
  accountName?: string;
  signalId: string;
  exchange: string;
  symbol: string;
  side: OrderSide;
  orderType: OrderType;
  quantity: string;
  price: string;
  filledQuantity: string;
  filledPrice: string;
  fee: string;
  status: OrderStatus;
  tradeMode: ExecutionMode;
  marketType?: MarketType;
  exchangeOrderId: string;
  errorMsg: string;
  createTime: string;
  updateTime: string;
}

/** 持仓 VO */
export interface PositionVO {
  id: string;
  strategyId: string;
  strategyName?: string;
  accountId: string;
  accountName?: string;
  exchange: string;
  symbol: string;
  side: PositionSide;
  quantity: string;
  entryPrice: string;
  currentPrice: string;
  unrealizedPnl: string;
  realizedPnl: string;
  stopLoss: string;
  takeProfit: string;
  closePrice: string;
  closedAt: string;
  status: PositionStatus;
  tradeMode: ExecutionMode;
  marketType?: MarketType;
  leverage?: number;
  marginType?: MarginType;
  liquidationPrice?: string;
  fundingRate?: string;
  createTime: string;
  updateTime: string;
}

/** 账户创建参数 */
export interface ExchangeAccountCreateDTO {
  name: string;
  exchange?: string;
  apiKey: string;
  apiSecret: string;
  marketType?: MarketType;
}

/** 账户更新参数 */
export interface ExchangeAccountUpdateDTO {
  id: string;
  name?: string;
  apiKey?: string;
  apiSecret?: string;
  marketType?: MarketType;
}

/** 手动下单参数 */
export interface OrderCreateDTO {
  accountId: string;
  exchange: string;
  symbol: string;
  side: OrderSide;
  orderType?: OrderType;
  quantity: number;
  price?: number;
  executionMode?: ExecutionMode;
}

/** 订单查询参数 */
export interface OrderQueryDTO extends PageQuery {
  strategyId?: string;
  exchange?: string;
  symbol?: string;
  side?: OrderSide;
  status?: OrderStatus;
  tradeMode?: ExecutionMode;
  startTime?: number;
  endTime?: number;
}

/** 持仓查询参数 */
export interface PositionQueryDTO extends PageQuery {
  strategyId?: string;
  exchange?: string;
  symbol?: string;
  status?: PositionStatus;
  tradeMode?: ExecutionMode;
  marketType?: MarketType;
}

/** 盈亏分析查询参数 */
export interface PnlAnalysisQueryDTO {
  tradeMode?: ExecutionMode;
  strategyId?: string;
  symbol?: string;
  accountId?: string;
  closedAtStart?: string;
  closedAtEnd?: string;
}

/** 按策略盈亏项 */
export interface StrategyPnlItem {
  strategyId: string | number;
  strategyName?: string;
  closedCount: number;
  realizedPnl: string | number;
}

/** 按交易对盈亏项 */
export interface SymbolPnlItem {
  symbol: string;
  closedCount: number;
  realizedPnl: string | number;
}

/** 盈亏分析结果 */
export interface PnlAnalysisVO {
  totalPnl: string | number;
  paperPnl: string | number;
  livePnl: string | number;
  byStrategy: StrategyPnlItem[];
  bySymbol: SymbolPnlItem[];
}

/** 账户资产余额 VO */
export interface AssetBalanceVO {
  asset: string;
  free: string | number;
  locked: string | number;
  total: string | number;
}

/** 每日盈亏数据点（走势图） */
export interface DailyPnlVO {
  date: string;
  dailyPnl: string | number;
  cumulativePnl: string | number;
}

// ─── API ─────────────────────────────────────────

/** 交易所账户 API */
export const exchangeAccountApi = {
  create: (data: ExchangeAccountCreateDTO): Promise<ApiResponse<string>> =>
    request.post('/trade/account', data),

  update: (data: ExchangeAccountUpdateDTO): Promise<ApiResponse<void>> =>
    request.put('/trade/account', data),

  delete: (id: string): Promise<ApiResponse<void>> =>
    request.delete(`/trade/account/${id}`),

  getById: (id: string): Promise<ApiResponse<ExchangeAccountVO>> =>
    request.get(`/trade/account/${id}`),

  list: (): Promise<ApiResponse<ExchangeAccountVO[]>> =>
    request.get('/trade/account/list'),

  testConnection: (id: string): Promise<ApiResponse<boolean>> =>
    request.post(`/trade/account/${id}/test`),

  getBalance: (id: string): Promise<ApiResponse<AssetBalanceVO[]>> =>
    request.get(`/trade/account/${id}/balance`),
};

/** 交易订单 API */
export const orderApi = {
  create: (data: OrderCreateDTO): Promise<ApiResponse<string>> =>
    request.post('/trade/order', data),

  confirm: (id: string): Promise<ApiResponse<void>> =>
    request.post(`/trade/order/${id}/confirm`),

  reject: (id: string): Promise<ApiResponse<void>> =>
    request.post(`/trade/order/${id}/reject`),

  cancel: (id: string): Promise<ApiResponse<void>> =>
    request.post(`/trade/order/${id}/cancel`),

  syncFill: (id: string): Promise<ApiResponse<void>> =>
    request.post(`/trade/order/${id}/sync-fill`),

  page: (params: OrderQueryDTO): Promise<ApiResponse<PageResult<OrderVO>>> =>
    request.get('/trade/order/page', { params }),

  getById: (id: string): Promise<ApiResponse<OrderVO>> =>
    request.get(`/trade/order/${id}`),
};

/** 持仓 API */
export const positionApi = {
  page: (params: PositionQueryDTO): Promise<ApiResponse<PageResult<PositionVO>>> =>
    request.get('/trade/position/page', { params }),

  getById: (id: string): Promise<ApiResponse<PositionVO>> =>
    request.get(`/trade/position/${id}`),

  close: (id: string): Promise<ApiResponse<void>> =>
    request.post(`/trade/position/${id}/close`),
};

/** 盈亏分析 API */
export const pnlAnalysisApi = {
  getAnalysis: (params?: PnlAnalysisQueryDTO): Promise<ApiResponse<PnlAnalysisVO>> =>
    request.get('/trade/pnl-analysis', { params }),

  getDailyPnl: (params?: PnlAnalysisQueryDTO): Promise<ApiResponse<DailyPnlVO[]>> =>
    request.get('/trade/pnl-analysis/daily', { params }),
};
