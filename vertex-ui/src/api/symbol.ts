import { request } from '../utils/request';
import type { ApiResponse } from '../types/api';

/**
 * 交易所币对 VO
 */
export interface ExchangeSymbolVO {
  id: string;
  /** 平台通用标识，如 ETHUSDT */
  symbol: string;
  /** 标的资产，如 ETH */
  baseAsset: string;
  /** 计价资产，如 USDT */
  quoteAsset: string;
  /** 交易所代码，如 binance */
  exchange: string;
  /** 市场类型：SPOT / USDM / COINM */
  marketType: string;
  /** 交易所实际标识 */
  exchangeSymbol: string;
  /** TRADING / BREAK */
  status: string;
  lotSize?: string;
  tickSize?: string;
}

export const symbolApi = {
  /**
   * 查询币对列表（前端下拉使用）
   * @param exchange   交易所代码，如 binance
   * @param marketType 市场类型过滤（可选）
   */
  list: (exchange?: string, marketType?: string) =>
    request.get<ApiResponse<ExchangeSymbolVO[]>>('/trade/symbol', { params: { exchange, marketType } }),

  /**
   * 手动触发全量同步
   */
  sync: () =>
    request.post<ApiResponse<number>>('/trade/symbol/sync'),
};
