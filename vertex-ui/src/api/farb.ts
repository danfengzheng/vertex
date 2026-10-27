import { request } from '../utils/request';
import type { ApiResponse } from '../types/api';

/**
 * 资金费套利策略 API（对应 /admin/farb/strategy/*）
 */

export type FarbStatus = 'STOPPED' | 'RUNNING' | 'PAUSED' | 'HIBERNATE';
export type FarbExecutionMode = 'PAPER' | 'LIVE';
export type FarbPairStatus =
  | 'PENDING_ENTRY'
  | 'OPEN'
  | 'CLOSING'
  | 'CLOSED'
  | 'CANCELED'
  | 'EMERGENCY_CLOSED'
  | 'ZOMBIE';

/** 策略主体 */
export interface FarbStrategyVO {
  id?: string;
  name: string;
  description?: string;

  spotAccountId: number;
  perpAccountId: number;
  status?: FarbStatus;
  executionMode?: FarbExecutionMode;

  scanAllMarket?: 0 | 1;
  symbolWhitelist?: string;
  symbolBlacklist?: string;
  min24hVolumeUsd?: number;

  lookbackSettlements?: number;
  minAvgFundingBps?: number;
  minHitRatio?: number;
  minLatestFundingBps?: number;
  maxEntryBasisBps?: number;
  lsrMaxExtreme?: number;
  oiCrowdingThresholdPct?: number;
  volSpikeMultiplier?: number;
  requireTakerConfirm?: 0 | 1;

  exitRegimeBreakCount?: number;
  exitBasisDeltaBps?: number;
  exitTakeProfitPct?: number;
  exitMaxHoldHours?: number;
  basisEmergencyBps?: number;

  notionalPerPair?: number;
  maxOpenPairs?: number;
  maxPairsPerSymbol?: number;
  perpLeverage?: number;
  perpMarginType?: 'ISOLATED' | 'CROSS';

  useRealtimeFee?: 0 | 1;
  useBnbDiscount?: 0 | 1;
  spotFeeRate?: number;
  perpFeeRate?: number;
  minExpectedYieldPct?: number;

  maxDrawdownPct?: number;
  fundingReversalStopDays?: number;

  // 预结算负 rate 检测
  preSettleEnabled?: 0 | 1;
  preSettleImminentMin?: number;
  preSettleStrongMin?: number;
  preSettleStrongBps?: number;

  // 强平保护
  liquidationProtectionEnabled?: 0 | 1;
  liquidationSafeMovePct?: number;

  // Dual-maker post-only
  dualMakerEnabled?: 0 | 1;
  dualMakerTimeoutMs?: number;
  dualMakerPollMs?: number;
  spotMakerFeeRate?: number;
  perpMakerFeeRate?: number;

  // v2 预测反转
  predictReversalEnabled?: 0 | 1;
  predictHardScore?: number;
  predictSoftScore?: number;
  predictBreakCountTightened?: number;
  predictTpTightenedPct?: number;
  predictPauseNewMin?: number;

  // Runtime snapshot
  currentOpenPairs?: number;
  totalFundingPnl?: number;
  totalBasisPnl?: number;
  totalFeePaid?: number;
  totalNetPnl?: number;
  totalPairsOpened?: number;
  totalPairsClosed?: number;

  createTime?: string;
  updateTime?: string;
}

export interface RegimeMetricsVO {
  symbol?: string;
  avgRecentFundingBps?: number;
  hitRatio?: number;
  latestFundingBps?: number;
  fundingMomentumBps?: number;
  fundingSampleCount?: number;
  currentBasisBps?: number;
  basisDelta2hBps?: number;
  currentOi?: number;
  oiChange2hPct?: number;
  oiChange24hPct?: number;
  lsr?: number;
  takerBsr?: number;
  vol24hAnnualized?: number;
  vol72hAnnualized?: number;
  volSpikeRatio?: number;
  hasCompleteData?: boolean;
  missingSignals?: string;
}

export interface ArbCandidateVO {
  symbol: string;
  score: number;
  metrics: RegimeMetricsVO;
  expectedNetYieldPct: number;
  expectedAnnualizedYieldPct: number;
  reason: string;
  generatedAt: number;
}

export interface FarbPairVO {
  id: string;
  strategyId: string;
  symbol: string;
  status: FarbPairStatus;
  entryTime?: string;
  entrySpotPrice?: number;
  entryPerpPrice?: number;
  entryBasisBps?: number;
  entryFundingRate?: number;
  spotQty?: number;
  perpQty?: number;
  notionalAtEntry?: number;
  projectedNetYieldPct?: number;
  nextExpectedSettlementTime?: string;
  lastSettlementTime?: string;
  settlementCount?: number;
  actualFundingPnl?: number;
  closeTime?: string;
  closeTrigger?: string;
  exitSpotPrice?: number;
  exitPerpPrice?: number;
  fundingPnl?: number;
  basisPnl?: number;
  feePaid?: number;
  netPnl?: number;
}

export interface FarbSettlementLogVO {
  id: string;
  strategyId: string;
  positionId: string;
  symbol: string;
  settlementTime: string;
  fundingRate: number;
  fundingIntervalHours: number;
  markPriceAtSettle?: number;
  perpQty: number;
  fundingAmount: number;
  source: string;
}

export interface FarbEventLogVO {
  id: string;
  strategyId: string;
  positionId?: string;
  symbol?: string;
  eventType: string;
  severity: 'DEBUG' | 'INFO' | 'WARN' | 'ERROR';
  payload?: string;
  occurredAt: string;
}

// ─── API ─────────────────────────────────────────────────────────────────

export const farbApi = {
  list: (): Promise<ApiResponse<FarbStrategyVO[]>> =>
    request.get('/farb/strategy'),
  get: (id: string | number): Promise<ApiResponse<FarbStrategyVO>> =>
    request.get(`/farb/strategy/${id}`),
  create: (dto: FarbStrategyVO): Promise<ApiResponse<string>> =>
    request.post('/farb/strategy', dto),
  update: (id: string | number, dto: Partial<FarbStrategyVO>): Promise<ApiResponse<void>> =>
    request.put(`/farb/strategy/${id}`, dto),
  delete: (id: string | number): Promise<ApiResponse<void>> =>
    request.delete(`/farb/strategy/${id}`),

  start: (id: string | number): Promise<ApiResponse<void>> =>
    request.post(`/farb/strategy/${id}/start`),
  stop: (id: string | number): Promise<ApiResponse<void>> =>
    request.post(`/farb/strategy/${id}/stop`),

  candidates: (id: string | number): Promise<ApiResponse<{
    lastScanAt: number;
    count: number;
    candidates: ArbCandidateVO[];
  }>> =>
    request.get(`/farb/strategy/${id}/candidates`),

  pairs: (
    id: string | number,
    params?: { status?: string; limit?: number },
  ): Promise<ApiResponse<FarbPairVO[]>> =>
    request.get(`/farb/strategy/${id}/pairs`, { params }),

  events: (
    id: string | number,
    limit = 200,
  ): Promise<ApiResponse<FarbEventLogVO[]>> =>
    request.get(`/farb/strategy/${id}/events`, { params: { limit } }),

  universe: (): Promise<ApiResponse<string[]>> =>
    request.get('/farb/strategy/universe'),

  signalStatus: (symbol: string): Promise<ApiResponse<Record<string, number>>> =>
    request.get(`/farb/strategy/signal/${symbol}`),

  regime: (
    symbol: string,
    params?: { lookback?: number; minFundingBps?: number },
  ): Promise<ApiResponse<RegimeMetricsVO>> =>
    request.get(`/farb/strategy/regime/${symbol}`, { params }),

  settlements: (
    pairId: string | number,
  ): Promise<ApiResponse<FarbSettlementLogVO[]>> =>
    request.get(`/farb/strategy/pair/${pairId}/settlements`),

  manualClose: (pairId: string | number): Promise<ApiResponse<void>> =>
    request.post(`/farb/strategy/pair/${pairId}/close`),

  forceOpen: (pairId: string | number): Promise<ApiResponse<void>> =>
    request.post(`/farb/strategy/pair/${pairId}/force-open`),
};

// ─── 回测 ──────────────────────────────────────────────────────────

export interface BacktestConfig {
  startTimeMs: number;
  endTimeMs: number;
  initialCapital?: number;
  notionalPerPair?: number;
  maxOpenPairs?: number;
  maxPairsPerSymbol?: number;
  lookbackSettlements?: number;
  minAvgFundingBps?: number;
  minHitRatio?: number;
  minLatestFundingBps?: number;
  maxEntryBasisBps?: number;
  exitRegimeBreakCount?: number;
  exitBasisDeltaBps?: number;
  exitTakeProfitPct?: number;
  exitMaxHoldHours?: number;
  spotFeeRate?: number;
  perpFeeRate?: number;
  slippagePerLegBps?: number;
  symbolWhitelist?: string[];
  symbolBlacklist?: string[];
  ignoreBasisPnl?: boolean;
}

export interface EquityPoint {
  ts: number;
  equity: number;
  drawdownPct: number;
}
export interface PairTrade {
  symbol: string;
  entryTs: number;
  exitTs: number;
  holdHours: number;
  settlementCount: number;
  fundingPnl: number;
  basisPnl: number;
  feePaid: number;
  netPnl: number;
  closeTrigger: string;
}
export interface SymbolPerf {
  symbol: string;
  pairCount: number;
  totalNetPnl: number;
  winRatePct: number;
  avgNetPnl: number;
}
export interface BacktestResult {
  config: BacktestConfig;
  startTimeMs: number;
  endTimeMs: number;
  universeSize: number;
  initialCapital: number;
  finalCapital: number;
  totalNetPnl: number;
  totalFundingPnl: number;
  totalBasisPnl: number;
  totalFeePaid: number;
  totalReturnPct: number;
  annualizedReturnPct: number;
  sharpe: number;
  maxDrawdownPct: number;
  totalPairsOpened: number;
  totalPairsClosed: number;
  winCount: number;
  lossCount: number;
  winRatePct: number;
  avgPairNetPnl: number;
  avgHoldingHours: number;
  avgSettlementsPerPair: number;
  closeTriggerDistribution: Record<string, number>;
  topSymbols: SymbolPerf[];
  bottomSymbols: SymbolPerf[];
  equityCurve: EquityPoint[];
  trades: PairTrade[];
  warnings: string[];
}

export const farbBacktestApi = {
  run: (config: BacktestConfig): Promise<ApiResponse<BacktestResult>> =>
    request.post('/farb/backtest/run', config),
};
