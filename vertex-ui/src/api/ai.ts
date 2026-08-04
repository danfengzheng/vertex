import { request } from '../utils/request';
import type { ApiResponse } from '../types/api';

/**
 * AI 分析 API
 */

export interface AiSignalAnalysis {
  confidence: number | null;
  alignment: 'ALIGNED' | 'NEUTRAL' | 'DIVERGED' | null;
  marketRegime: 'TRENDING' | 'RANGING' | 'VOLATILE' | 'CALM' | null;
  keyFactors: string[] | null;
  risks: string[] | null;
  suggestedAction:
    | 'ENTER_FULL'
    | 'ENTER_HALF'
    | 'ENTER_WITH_TIGHT_STOP'
    | 'OBSERVE'
    | 'SKIP'
    | null;
  summary: string | null;
  model: string | null;
  analyzedAt: number | null;
  durationMs: number | null;
  errorMessage: string | null;
}

export interface AiTradeAnalysis {
  tradeIndex: number;
  entryTime: number | null;
  exitTime: number | null;
  quality: number | null;
  verdict:
    | 'GOOD_ENTRY'
    | 'LATE_ENTRY'
    | 'FALSE_SIGNAL'
    | 'GOOD_EXIT'
    | 'EARLY_EXIT'
    | 'BAD_STOP_LOSS'
    | 'LUCKY_PROFIT'
    | null;
  entryFactors: string[] | null;
  exitFactors: string[] | null;
  improvements: string[] | null;
  summary: string | null;
  model: string | null;
  analyzedAt: number | null;
  durationMs: number | null;
  errorMessage: string | null;
}

export interface AiBacktestProgress {
  cacheKey: string;
  strategyId: number;
  strategyName: string;
  total: number;
  completed: number;
  failed: number;
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
  startedAt: number;
  updatedAt: number;
  completedAt: number | null;
  errorMessage: string | null;
}

export interface AiStatus {
  cacheEnabled: boolean;
  aiEnabled: boolean;
  /** 当前生效的 AI provider：'gemini' / 'deepseek' / null */
  provider?: string | null;
  /** 当前生效的模型名 */
  model?: string | null;
  /** 线程池：工作线程数 */
  workerThreads?: number;
  /** 线程池：队列容量 */
  queueCapacity?: number;
  /** 线程池：当前队列 pending 任务数 */
  queueSize?: number;
  /** 线程池：当前正在执行的任务数 */
  activeCount?: number;
  /** 线程池：累计完成任务数 */
  completedTaskCount?: number;
  /** 线程池：累计被丢弃的任务数（队列满拒绝） */
  rejectedTaskCount?: number;
}

/** AI 仪表盘里实时信号 AI 分析的一行（含信号上下文） */
export interface AiSignalAnalysisRow {
  signalId: number;
  strategyId?: number | null;
  strategyName?: string | null;
  exchange?: string | null;
  symbol?: string | null;
  interval?: string | null;
  signalType?: 'BUY' | 'SELL' | 'NEUTRAL' | null;
  signalStrength?: number | null;
  signalTime?: number | null;
  price?: string | null;
  analysis: AiSignalAnalysis;
}

/** AI 仪表盘里回测 Trade AI 分析的一行（含回测/trade 上下文） */
export interface AiTradeAnalysisRow {
  cacheKey: string;
  tradeIndex: number;
  strategyId?: number | null;
  strategyName?: string | null;
  exchange?: string | null;
  symbol?: string | null;
  interval?: string | null;
  entryTime?: number | null;
  exitTime?: number | null;
  type?: 'LONG' | 'SHORT' | null;
  profit?: string | null;
  profitPercent?: string | null;
  analysis: AiTradeAnalysis;
}

/**
 * AI 动态配置（对应 DB 单行表 ai_config，UI 可编辑，5s 生效）。
 * yaml 里只有 bean 级 enabled + 线程池尺寸，剩下全在这里。
 */
export interface AiConfigVO {
  id?: number;
  enabled: 0 | 1;
  provider: 'gemini' | 'deepseek';
  language: string;
  geminiApiKey?: string | null;
  geminiModel: string;
  geminiBaseUrl: string;
  geminiTimeoutSeconds: number;
  geminiMaxRetry: number;
  deepseekApiKey?: string | null;
  deepseekModel: string;
  deepseekBaseUrl: string;
  deepseekTimeoutSeconds: number;
  deepseekMaxRetry: number;
  /** null=模型默认 / 0=显式关思考（快）/ 1=显式开思考（慢） */
  deepseekThinkingEnabled?: 0 | 1 | null;
  /** low / medium / high；仅 thinking=enabled 生效 */
  deepseekReasoningEffort?: 'low' | 'medium' | 'high' | null;
  createTime?: string;
  updateTime?: string;
  updateBy?: number | null;
}

export const aiApi = {
  /** AI 模块状态 */
  status: (): Promise<ApiResponse<AiStatus>> => request.get('/ai/status'),

  /** 读取 AI 动态配置 */
  getConfig: (): Promise<ApiResponse<AiConfigVO>> => request.get('/ai/config'),

  /** 保存 AI 动态配置（5s 内生效，无需重启） */
  updateConfig: (cfg: AiConfigVO): Promise<ApiResponse<AiConfigVO>> =>
    request.put('/ai/config', cfg),

  /** 实时信号 AI 分析 */
  getSignalAnalysis: (signalId: string | number): Promise<ApiResponse<AiSignalAnalysis | null>> =>
    request.get(`/ai/signal/${signalId}`),

  /** 手动触发/重新触发单条信号的 AI 分析（异步，立即返回） */
  analyzeSignal: (signalId: string | number): Promise<ApiResponse<boolean>> =>
    request.post(`/ai/signal/${signalId}/analyze`),

  /** 回测 AI 分析进度 */
  getBacktestProgress: (cacheKey: string): Promise<ApiResponse<AiBacktestProgress | null>> =>
    request.get(`/ai/backtest/${cacheKey}/progress`),

  /** 回测全部 trade AI 分析 */
  getBacktestTrades: (cacheKey: string): Promise<ApiResponse<AiTradeAnalysis[]>> =>
    request.get(`/ai/backtest/${cacheKey}/trades`),

  /** 回测单笔 trade AI 分析 */
  getBacktestTrade: (
    cacheKey: string,
    tradeIndex: number,
  ): Promise<ApiResponse<AiTradeAnalysis | null>> =>
    request.get(`/ai/backtest/${cacheKey}/trades/${tradeIndex}`),

  /** 清除回测缓存 */
  clearBacktestCache: (cacheKey: string): Promise<ApiResponse<number>> =>
    request.delete(`/ai/backtest/${cacheKey}`),

  /** 手动触发/重新触发回测 AI 批量分析（不重跑回测） */
  retriggerBacktestAnalysis: (cacheKey: string): Promise<ApiResponse<boolean>> =>
    request.post(`/ai/backtest/${cacheKey}/analyze`),

  /** AI 仪表盘：最近 N 条实时信号 AI 分析（按 analyzedAt 降序） */
  listSignalAnalyses: (limit = 100): Promise<ApiResponse<AiSignalAnalysisRow[]>> =>
    request.get(`/ai/dashboard/signals`, { params: { limit } }),

  /** AI 仪表盘：最近 N 条回测 Trade AI 分析（跨所有回测，按 analyzedAt 降序） */
  listTradeAnalyses: (limit = 100): Promise<ApiResponse<AiTradeAnalysisRow[]>> =>
    request.get(`/ai/dashboard/trades`, { params: { limit } }),
};
