import { request } from '../utils/request';
import type { ApiResponse } from '../types/api';

/**
 * 信号清理 API
 * 对应后端 SignalCleanupController，路由前缀 /admin/signal/cleanup。
 */

export interface SignalCleanupConfigVO {
  id?: number;
  enabled: 0 | 1;

  /** null = 该类不清理 */
  keepNeutralDays: number | null;
  keepDirectionalDays: number | null;
  keepLinkedDays: number | null;

  protectRecentDays: number;

  scheduleCron: string;
  deleteMode: 'SOFT' | 'HARD';
  batchSize: number;

  // 最近一次运行统计（只读，后端 updateLastRun 写入）
  lastRunAt?: string | null;
  lastRunDeletedNeutral?: number | null;
  lastRunDeletedDirectional?: number | null;
  lastRunDeletedLinked?: number | null;
  lastRunDurationMs?: number | null;
  lastRunError?: string | null;

  createTime?: string;
  updateTime?: string;
  updateBy?: number | null;
}

export interface SignalCleanupPreviewVO {
  totalActive: number;
  willDeleteNeutral: number;
  willDeleteDirectionalOrphan: number;
  willDeleteLinked: number;
  willDeleteTotal: number;
  afterCleanup: number;

  neutralCutoffMs: number | null;
  directionalCutoffMs: number | null;
  linkedCutoffMs: number | null;
  protectCutoffMs: number;
}

export interface SignalCleanupRunResultVO {
  trigger: 'MANUAL' | 'SCHEDULED';
  deleteMode: 'SOFT' | 'HARD';
  deletedNeutral: number;
  deletedDirectionalOrphan: number;
  deletedLinked: number;
  deletedTotal: number;
  rocksdbAiAnalysisDeleted: number;
  startedAt: number;
  finishedAt: number;
  durationMs: number;
  errorMessage: string | null;
}

export const signalCleanupApi = {
  getConfig: (): Promise<ApiResponse<SignalCleanupConfigVO>> =>
    request.get('/signal/cleanup/config'),
  updateConfig: (cfg: SignalCleanupConfigVO): Promise<ApiResponse<SignalCleanupConfigVO>> =>
    request.put('/signal/cleanup/config', cfg),
  preview: (): Promise<ApiResponse<SignalCleanupPreviewVO>> =>
    request.get('/signal/cleanup/preview'),
  run: (): Promise<ApiResponse<SignalCleanupRunResultVO>> =>
    request.post('/signal/cleanup/run'),
};
