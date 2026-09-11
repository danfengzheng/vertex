import { request } from '../utils/request';
import type { ApiResponse } from '../types/api';

/**
 * 做市 - 网格策略 API（对应 /admin/mm/grid/*）
 */

export type GridStatus = 'STOPPED' | 'RUNNING' | 'PAUSED' | 'EMERGENCY_STOPPED';
export type EmergencyMode = 'SOFT_STOP' | 'GRACEFUL_CLOSE' | 'LIMIT_LADDER_CLOSE' | 'MARKET_FALLBACK';
export type ProtectionLayer = 'NORMAL' | 'LAYER1' | 'LAYER2' | 'LAYER3' | 'LAYER4' | 'EMERGENCY';
export type ExecutionMode = 'PAPER' | 'LIVE';

/** 网格策略实体（对应后端 GridStrategy）*/
export interface GridStrategyVO {
  // ⚠️ 后端 Long 序列化为 string 防精度丢失
  id?: string;
  name: string;
  description?: string;
  exchange: string;
  symbol: string;
  accountId: number;
  status?: GridStatus;

  centerPrice?: number | null;
  stepPct?: number | null;
  windowSizePerSide: number;
  qtyPerGrid: number;

  baseStepPct: number;
  minimumStepSafetyMultiplier?: number;

  maxPositionQty: number;
  maxNotionalUsd?: number | null;
  maxDeltaQty?: number | null;

  hedgeMode?: 0 | 1;
  longSideEnabled?: 0 | 1;
  shortSideEnabled?: 0 | 1;
  adaptiveStepEnabled?: 0 | 1;
  trendProtectionEnabled?: 0 | 1;

  trendLayer1Interval?: string;
  trendLayer2Interval?: string;
  trendLayer3Interval?: string;
  trendLayer4Interval?: string;

  trendLayer2AdxEnter?: number;
  trendLayer2AdxExit?: number;
  trendLayer3AdxEnter?: number;
  trendLayer3AdxExit?: number;
  trendLayer4AdxEnter?: number;

  longReducePnlPct?: number;
  shortReducePnlPct?: number;
  longStopPnlPct?: number;
  shortStopPnlPct?: number;
  accountEmergencyPnlPct?: number;
  /** 账户级 pnl 自动 emergencyStop 开关；0=关(默认) 1=开 */
  riskAutoStopEnabled?: 0 | 1;

  ladderPctOfPosition?: number;
  ladderStepCount?: number;
  ladderMinReservePct?: number;

  reshuffleCooldownSec?: number;
  emergencyTakerFallbackEnabled?: 0 | 1;
  emergencyTakerLossThreshold?: number;

  leverage: number;
  marginType?: 'ISOLATED' | 'CROSS';

  // Paper trading + Smart pricing
  executionMode?: ExecutionMode;
  paperQueueLossFactor?: number;
  paperGtxRejectRate?: number;
  paperSmartPricingEnabled?: 0 | 1;
  paperMinLiquidityQty?: number;

  // 运行时快照
  lastReshuffleAt?: string;
  lastAdaptiveAt?: string;
  currentActiveLayer?: ProtectionLayer;
  currentLongQty?: number;
  currentShortQty?: number;
  totalArbitragePnl?: number;
  totalDirectionalPnl?: number;
  totalFeePaid?: number;

  createTime?: string;
  updateTime?: string;
}

export interface DepletionState {
  strategyId?: number;
  longDepletionPct?: number;
  shortDepletionPct?: number;
  longSpeedPer3Min?: number;
  shortSpeedPer3Min?: number;
  longSpeedPer30Min?: number;
  shortSpeedPer30Min?: number;
  asymmetryRatio?: number;
  consecutiveSameSide?: number;
  consecutiveSide?: string;
  secondsSinceLastReverseFill?: number;
  trendScore?: number;
  breakdown?: {
    adxScore?: number;
    depletionScore?: number;
    asymmetryScore?: number;
    consecutiveScore?: number;
    reverseGapScore?: number;
  };
}

export interface GridStatusVO {
  strategy: GridStrategyVO;
  /** 实时权威值 —— 优先展示这些字段而不是 strategy.centerPrice 等 DB 快照 */
  realtimeCenterPrice?: number | null;
  realtimeStepPct?: number | null;
  realtimeLongQty?: number | null;
  realtimeShortQty?: number | null;
  activeOrders: number;
  longActiveOrders: number;
  shortActiveOrders: number;
  currentLayer: ProtectionLayer;
  depletion?: DepletionState;
  markPrice?: number | null;
  spread?: number | null;
  spreadPct?: number | null;
}

export interface GridOrderVO {
  id: string;
  gridStrategyId: string;
  exchangeOrderId?: string;
  clientOrderId?: string;
  side: 'BUY' | 'SELL';
  positionSide: 'LONG' | 'SHORT';
  role: 'OPEN' | 'CLOSE' | 'LADDER_CLOSE';
  gridIndex?: number;
  gridPrice: number;
  qty: number;
  status: 'PENDING' | 'LIVE' | 'FILLED' | 'PARTIAL' | 'CANCELED' | 'REJECTED' | 'UNKNOWN_RECONCILE';
  filledQty?: number;
  avgFillPrice?: number;
  feePaid?: number;
  pairOrderId?: string;
  placedAt?: string;
  filledAt?: string;
  canceledAt?: string;
  createTime?: string;
}

export interface GridEventVO {
  id: string;
  gridStrategyId: string;
  eventType: string;
  severity?: 'INFO' | 'WARN' | 'ERROR';
  payloadJson?: string;
  occurredAt?: string;
}

export interface GridPnlSnapshotVO {
  id: string;
  gridStrategyId: string;
  snapshotAt: string;
  markPrice?: number;
  longQty?: number;
  shortQty?: number;
  netQty?: number;
  netNotional?: number;
  longAvgCost?: number;
  shortAvgCost?: number;
  unrealizedPnl?: number;
  realizedArbitragePnl?: number;
  realizedDirectionalPnl?: number;
  fundingPnl?: number;
  feePaid?: number;
  roundTripsCompleted?: number;
  activeOrdersCount?: number;
  longDepletionPct?: number;
  shortDepletionPct?: number;
  currentLayer?: ProtectionLayer;
  trendScore?: number;
}

// ─── 回测 ────────────────────────────────────────

export interface BacktestConfig {
  startTime?: number;
  endTime?: number;
  klineInterval?: string;
  initialCapital?: number;
  feeRate?: number;
  simulateProtection?: boolean;
}

export interface BacktestEquityPoint {
  timeMs: number;
  capital: number;
  arbPnl: number;
  dirPnl: number;
  longQty: number;
  shortQty: number;
  currentLayer?: string;
}

export interface BacktestResult {
  strategyId: string;
  symbol: string;
  startTime: number;
  endTime: number;
  klineInterval: string;
  initialCapital: number;
  finalCapital: number;
  totalReturn: number;
  totalReturnPct: number;
  totalArbitragePnl: number;
  totalDirectionalPnl: number;
  totalFeePaid: number;
  totalRoundTrips: number;
  totalOpenFills: number;
  totalCloseFills: number;
  layer2Triggers: number;
  layer3Triggers: number;
  layer4Triggers: number;
  maxDrawdown: number;
  maxDrawdownPct: number;
  maxDrawdownDurationMs: number;
  maxLongQty: number;
  maxShortQty: number;
  maxNetQty: number;
  equityCurve?: BacktestEquityPoint[];
  events?: string[];
}

export const mmGridApi = {
  // ⚠️ id 一律用 string —— Long 型 id 超过 Number.MAX_SAFE_INTEGER，Number() 会丢精度导致 404
  list: (): Promise<ApiResponse<GridStrategyVO[]>> => request.get('/mm/grid'),
  get: (id: string | number): Promise<ApiResponse<GridStrategyVO>> => request.get(`/mm/grid/${id}`),
  create: (dto: GridStrategyVO): Promise<ApiResponse<string>> => request.post('/mm/grid', dto),
  update: (id: string | number, dto: Partial<GridStrategyVO>): Promise<ApiResponse<void>> =>
    request.put(`/mm/grid/${id}`, dto),
  delete: (id: string | number): Promise<ApiResponse<void>> => request.delete(`/mm/grid/${id}`),

  start: (id: string | number, initialCenterPrice?: number): Promise<ApiResponse<void>> =>
    request.post(`/mm/grid/${id}/start`, { initialCenterPrice }),
  stop: (id: string | number): Promise<ApiResponse<void>> => request.post(`/mm/grid/${id}/stop`),
  emergency: (id: string | number, mode: EmergencyMode, reason?: string): Promise<ApiResponse<void>> =>
    request.post(`/mm/grid/${id}/emergency`, { mode, reason }),
  recover: (id: string | number, note?: string): Promise<ApiResponse<void>> =>
    request.post(`/mm/grid/${id}/recover`, { note }),

  status: (id: string | number): Promise<ApiResponse<GridStatusVO>> => request.get(`/mm/grid/${id}/status`),
  orders: (id: string | number, params?: { status?: string; limit?: number }): Promise<ApiResponse<GridOrderVO[]>> =>
    request.get(`/mm/grid/${id}/orders`, { params }),
  events: (id: string | number, limit = 200): Promise<ApiResponse<GridEventVO[]>> =>
    request.get(`/mm/grid/${id}/events`, { params: { limit } }),
  pnl: (id: string | number, limit = 500): Promise<ApiResponse<GridPnlSnapshotVO[]>> =>
    request.get(`/mm/grid/${id}/pnl`, { params: { limit } }),
  backtest: (id: string | number, config: BacktestConfig): Promise<ApiResponse<BacktestResult>> =>
    request.post(`/mm/grid/${id}/backtest`, config),
};
