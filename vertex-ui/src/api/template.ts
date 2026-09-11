import { request } from '../utils/request';
import type { ApiResponse } from '../types/api';

/**
 * 策略模板 API — 对应后端 /admin/strategy-template/*
 */

export interface TemplateExposedParam {
  userKey: string;
  label?: string;
  labelEn?: string;
  description?: string;
  type: 'string' | 'number' | 'bool' | 'enum';
  required?: boolean;
  default?: unknown;
  min?: number;
  max?: number;
  step?: number;
  options?: unknown[];
  templatePath: string;
}

export interface StrategyTemplateVO {
  // ⚠️ Long id 序列化为 string 防精度丢失
  id: string;
  code: string;
  name: string;
  description?: string;
  category?: string;
  version: number;
  status: 'DRAFT' | 'PUBLISHED' | 'DEPRECATED';
  riskProfile?: 'LOW' | 'MEDIUM' | 'HIGH';
  marketFit?: string[];
  intervalFit?: string[];
  /** 完整模板骨架 —— 前端 JSON 编辑器展示 */
  template?: Record<string, unknown>;
  exposedParams?: TemplateExposedParam[];
  minEngineVersion?: string;
  schemaVersion?: number;
  isBuiltin?: 0 | 1;
  createTime?: string;
  updateTime?: string;
}

export interface TemplateSaveDTO {
  id?: string;
  code?: string;
  name?: string;
  description?: string;
  category?: string;
  status?: string;
  riskProfile?: string;
  marketFit?: string[];
  intervalFit?: string[];
  template?: unknown;
  exposedParams?: unknown[];
  minEngineVersion?: string;
  schemaVersion?: number;
  publish?: boolean;
  changeNote?: string;
}

export interface TemplateBuildDTO {
  templateId: string;
  userParams: Record<string, unknown>;
  strategyName?: string;
  backtestStartTime?: number;
  backtestEndTime?: number;
  backtestInitialCapital?: number;
}

export interface TemplateValidationResult {
  valid: boolean;
  errors: string[];
  warnings: string[];
}

export interface TemplateHistoryVO {
  id: string;
  templateId: string;
  version: number;
  templateJsonSnapshot: string;
  exposedParamsSnapshot: string;
  changeNote?: string;
  changedBy?: string;
  changedAt: string;
}

export interface BacktestResultLite {
  returnRate?: number;
  totalProfit?: number;
  totalTrades?: number;
  winRate?: number;
  maxDrawdown?: number;
  sharpeRatio?: number;
  profitLossRatio?: number;
  initialCapital?: number;
  finalCapital?: number;
  cached?: boolean;
  cacheKey?: string;
  [k: string]: unknown;
}

export interface AbCompareCandidate {
  label: string;
  templateId: string;
  templateVersion?: number;
  returnRate?: number;
  maxDrawdown?: number;
  winRate?: number;
  totalTrades?: number;
  sharpeRatio?: number;
  profitLossRatio?: number;
  full?: BacktestResultLite;
  userParams?: Record<string, unknown>;
  errorMessage?: string | null;
}

export interface AbCompareResult {
  candidates: AbCompareCandidate[];
  winnerIndex: number | null;
}

export interface AbCompareRequest {
  candidates: TemplateBuildDTO[];
  startTime?: number;
  endTime?: number;
  initialCapital?: number;
}

export const templateApi = {
  list: (params?: { status?: string; category?: string }): Promise<ApiResponse<StrategyTemplateVO[]>> =>
    request.get('/strategy-template', { params }),
  get: (id: string | number): Promise<ApiResponse<StrategyTemplateVO>> =>
    request.get(`/strategy-template/${id}`),
  getByCode: (code: string): Promise<ApiResponse<StrategyTemplateVO>> =>
    request.get(`/strategy-template/by-code/${code}`),
  save: (dto: TemplateSaveDTO): Promise<ApiResponse<StrategyTemplateVO>> =>
    request.post('/strategy-template', dto),
  delete: (id: string | number): Promise<ApiResponse<void>> =>
    request.delete(`/strategy-template/${id}`),
  history: (id: string | number): Promise<ApiResponse<TemplateHistoryVO[]>> =>
    request.get(`/strategy-template/${id}/history`),
  validate: (dto: TemplateSaveDTO): Promise<ApiResponse<TemplateValidationResult>> =>
    request.post('/strategy-template/validate', dto),

  preview: (dto: TemplateBuildDTO): Promise<ApiResponse<Record<string, unknown>>> =>
    request.post('/strategy-template/preview', dto),
  build: (dto: TemplateBuildDTO): Promise<ApiResponse<{ id: string; name: string }>> =>
    request.post('/strategy-template/build', dto),
  quickBacktest: (dto: TemplateBuildDTO): Promise<ApiResponse<BacktestResultLite>> =>
    request.post('/strategy-template/quick-backtest', dto),

  abCompare: (req: AbCompareRequest): Promise<ApiResponse<AbCompareResult>> =>
    request.post('/strategy-template/ab-compare', req),
};
