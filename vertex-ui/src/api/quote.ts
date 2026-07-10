import { request } from '../utils/request';
import type { ApiResponse } from '../types/api';

/**
 * 行情相关 API
 */

/** K线周期枚举 */
export type KLineInterval =
  | 'M1' | 'M3' | 'M5' | 'M15' | 'M30'
  | 'H1' | 'H2' | 'H4' | 'H6' | 'H8' | 'H12'
  | 'D1' | 'D3' | 'W1' | 'MN1';

/** K线周期显示映射 */
export const KLINE_INTERVAL_LABELS: Record<KLineInterval, string> = {
  M1: '1m', M3: '3m', M5: '5m', M15: '15m', M30: '30m',
  H1: '1h', H2: '2h', H4: '4h', H6: '6h', H8: '8h', H12: '12h',
  D1: '1d', D3: '3d', W1: '1w', MN1: '1M',
};

/** 数据源状态 VO */
export interface DataSourceStatusVO {
  exchange: string;
  connected: boolean;
}

/** K线数据 VO */
export interface KLineVO {
  symbol: string;
  exchange: string;
  interval: KLineInterval;
  openTime: number;
  closeTime: number;
  open: string;
  high: string;
  low: string;
  close: string;
  volume: string;
  quoteVolume: string;
  trades: number;
  closed: boolean;
}

/** K线查询参数（补全接口 interval 可为 null 表示全部周期） */
export interface KLineQueryDTO {
  symbol: string;
  exchange: string;
  interval?: KLineInterval | null;
  startTime?: number;
  endTime?: number;
  limit?: number;
}

/** 订阅请求参数 */
export interface SubscribeRequestDTO {
  exchange: string;
  symbol: string;
  interval: KLineInterval;
}

/** 活跃订阅项 */
export interface SubscriptionItem {
  symbol: string;
  interval: string;
}

/** 补全任务进度 */
export interface BackfillProgressVO {
  status: 'running' | 'done' | 'error' | 'not_found';
  totalIntervals?: number;
  completedIntervals?: number;
  completedRecords?: number;
  currentInterval?: string;
  errorMessage?: string;
}

/** 数据源管理 API */
export const quoteSourceApi = {
  /** 查看所有数据源状态 */
  status: (): Promise<ApiResponse<DataSourceStatusVO[]>> => {
    return request.get('/quote/source/status');
  },

  /** 启动数据源 */
  start: (exchange: string): Promise<ApiResponse<void>> => {
    return request.post(`/quote/source/start?exchange=${exchange}`);
  },

  /** 停止数据源 */
  stop: (exchange: string): Promise<ApiResponse<void>> => {
    return request.post(`/quote/source/stop?exchange=${exchange}`);
  },

  /** 查询活跃订阅列表 */
  subscriptions: (exchange: string): Promise<ApiResponse<SubscriptionItem[]>> => {
    return request.get('/quote/source/subscriptions', { params: { exchange } });
  },

  /** 订阅K线 */
  subscribe: (data: SubscribeRequestDTO): Promise<ApiResponse<void>> => {
    return request.post('/quote/source/subscribe', data);
  },

  /** 取消订阅K线 */
  unsubscribe: (data: SubscribeRequestDTO): Promise<ApiResponse<void>> => {
    return request.post('/quote/source/unsubscribe', data);
  },

  /** 历史K线补全（异步，返回 taskId） */
  backfill: (data: KLineQueryDTO): Promise<ApiResponse<string>> => {
    return request.post('/quote/source/backfill', data);
  },

  /** 查询补全任务进度 */
  backfillProgress: (taskId: string): Promise<ApiResponse<BackfillProgressVO>> => {
    return request.get('/quote/source/backfill/progress', { params: { taskId } });
  },
};

/** K线数据查询 API */
export const klineApi = {
  /** 查询K线数据 */
  query: (params: KLineQueryDTO): Promise<ApiResponse<KLineVO[]>> => {
    return request.get('/quote/kline', { params });
  },

  /** 获取最新K线 */
  latest: (symbol: string, exchange: string, interval: KLineInterval): Promise<ApiResponse<KLineVO>> => {
    return request.get('/quote/kline/latest', { params: { symbol, exchange, interval } });
  },
};

// ─── 成交量暴增扫描器 ─────────────────────────────────────────

/** 币安现货成交量暴增扫描器动态配置 */
export interface VolumeSurgeConfig {
  id?: number;
  enabled: 0 | 1;
  scanIntervalMinutes: number;
  quoteCurrency: string;
  surgeRatioThreshold: number | string;
  minPriceChange1hPct: number | string;
  baselineHours: number;
  minBaselineMedianUsdt: number | string;
  min24hQuoteVolumeUsdt: number | string;
  max24hQuoteVolumeUsdt: number | string;
  prefilterMinAbs24hPriceChangePct: number | string;
  excludeDaysSinceListing: number;
  cooldownHours: number;
  alertDirections: 'UP' | 'DOWN' | 'BOTH';
  /** 1=用未收盘 1H bar 判定（实时），0=只判已收盘 bar（事后 30-60min 确认） */
  includeUnclosedBar: 0 | 1;
  symbolBlacklist?: string | null;
  symbolWhitelist?: string | null;
  telegramEnabled: 0 | 1;
  telegramBotToken?: string | null;
  telegramChatId?: string | null;
  createTime?: string;
  updateTime?: string;
  updateBy?: number | null;
}

/** 扫描器运行时状态 */
export interface VolumeSurgeStatus {
  installed: boolean;
  lastScanAt: number;
  lastAlertCount: number;
}

/** 成交量暴增扫描器 API */
export const volumeSurgeApi = {
  getConfig: (): Promise<ApiResponse<VolumeSurgeConfig>> =>
    request.get('/quote/volume-surge/config'),
  updateConfig: (cfg: VolumeSurgeConfig): Promise<ApiResponse<VolumeSurgeConfig>> =>
    request.put('/quote/volume-surge/config', cfg),
  status: (): Promise<ApiResponse<VolumeSurgeStatus>> =>
    request.get('/quote/volume-surge/status'),
};
