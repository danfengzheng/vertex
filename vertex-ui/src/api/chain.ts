import { request } from '../utils/request';
import type { ApiResponse, PageResult, PageQuery } from '../types/api';

/**
 * 链上新币分析模块 API
 */

// ─── 类型定义 ─────────────────────────────────────

export type ChainCode = 'BNB' | 'SOL' | 'ALL';
export type DataSource = 'bnb_primary' | 'bnb_alpha' | 'bnb_trending' | 'SOL';
export type TokenStatus = 'PENDING' | 'SCORED' | 'ALERTED' | 'IGNORED';

/** 链上新币列表 VO */
export interface ChainTokenVO {
  id: string;
  chain: ChainCode;
  contractAddress: string;
  symbol: string;
  name: string;
  deployTime: number;
  score: number;
  scoreOnchain: number;
  scoreMarket: number;
  scoreTokenomics: number;
  scoreNovelty: number;
  status: TokenStatus;
  alerted: number;
  priceUsd: string | null;
  marketCapUsd: string | null;
  liquidityUsd: string | null;
  volume24hUsd: string | null;
  holderCount: number | null;
  createTime: string;
  dataSource: DataSource | null;
}

/** 链上新币详情 VO */
export interface ChainTokenDetailVO extends ChainTokenVO {
  decimals: number;
  totalSupply: string | null;
  deployerAddress: string | null;
  deployBlock: number | null;
  pairAddress: string | null;
  quoteToken: string | null;
  priceChange1hPct: string | null;
  priceChange24hPct: string | null;
  buyPressure1h: string | null;
  top10HolderPct: string | null;
  deployerHoldingPct: string | null;
  lpPoolPct: string | null;
  ageMinutes: number | null;
  pumpFunListed: number;
  liquidityLocked: number;
  contractVerified: number;
  txCount1h: number | null;
  lpAddCount: number | null;
  // 一级市场专属
  bondingCurveProgress: string | null;
  replyCount: number | null;
  launchpadName: string | null;
}

/** 告警规则 VO */
export interface AlertRuleVO {
  id: string;
  name: string;
  chain: ChainCode;
  minScore: number;
  minMarketCapUsd: string | null;
  minLiquidityUsd: string | null;
  minHolderCount: number | null;
  maxTop10HolderPct: string | null;
  notifyChannels: string;
  requireLiquidityLocked: number | null;
  enabled: number;
  description: string | null;
  createTime: string;
  updateTime: string;
}

/** 新币列表查询参数 */
export interface ChainTokenQueryDTO extends PageQuery {
  chain?: ChainCode;
  symbol?: string;
  minScore?: number;
  status?: TokenStatus;
  deployTimeFrom?: number;
  deployTimeTo?: number;
  dataSource?: DataSource;
}

/** 创建告警规则参数 */
export interface AlertRuleCreateDTO {
  name: string;
  chain?: ChainCode;
  minScore: number;
  minMarketCapUsd?: number;
  minLiquidityUsd?: number;
  minHolderCount?: number;
  maxTop10HolderPct?: number;
  notifyChannels?: string;
  requireLiquidityLocked?: number;
  description?: string;
}

/** 更新告警规则参数 */
export interface AlertRuleUpdateDTO extends AlertRuleCreateDTO {
  id: string;
}

// ─── API ─────────────────────────────────────────

/** 链上新币 API */
export const chainTokenApi = {
  /** 分页查询新币列表 */
  page: (params: ChainTokenQueryDTO): Promise<ApiResponse<PageResult<ChainTokenVO>>> =>
    request.get('/chain/token/page', { params }),

  /** 查询新币详情 */
  getById: (id: string): Promise<ApiResponse<ChainTokenDetailVO>> =>
    request.get(`/chain/token/${id}`),

  /** 手动触发扫描 */
  scan: (chain: string = 'ALL'): Promise<ApiResponse<void>> =>
    request.post('/chain/token/scan', null, { params: { chain } }),
};

/** 告警规则 API */
export const alertRuleApi = {
  /** 分页查询 */
  page: (params: PageQuery): Promise<ApiResponse<PageResult<AlertRuleVO>>> =>
    request.get('/chain/alert-rule/page', { params }),

  /** 查询详情 */
  getById: (id: string): Promise<ApiResponse<AlertRuleVO>> =>
    request.get(`/chain/alert-rule/${id}`),

  /** 创建 */
  create: (data: AlertRuleCreateDTO): Promise<ApiResponse<string>> =>
    request.post('/chain/alert-rule', data),

  /** 更新 */
  update: (data: AlertRuleUpdateDTO): Promise<ApiResponse<void>> =>
    request.put('/chain/alert-rule', data),

  /** 删除 */
  delete: (id: string): Promise<ApiResponse<void>> =>
    request.delete(`/chain/alert-rule/${id}`),

  /** 启用 */
  enable: (id: string): Promise<ApiResponse<void>> =>
    request.post(`/chain/alert-rule/${id}/enable`),

  /** 禁用 */
  disable: (id: string): Promise<ApiResponse<void>> =>
    request.post(`/chain/alert-rule/${id}/disable`),
};
