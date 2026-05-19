import { request } from '../utils/request';
import type { ApiResponse } from '../types/api';

/**
 * 用户个人设置 API
 */

export interface UserSettingVO {
  /** 单笔开仓最大使用资金（U/USDT），>0 启用，<=0 或 null 不限制 */
  maxTradeCapital: number | null;
}

export interface UserSettingSaveDTO {
  /** 单笔开仓最大使用资金（U/USDT），>0 启用，<=0 或 null 表示清空（不限制） */
  maxTradeCapital: number | null;
}

export const userSettingApi = {
  /** 获取当前用户个人设置 */
  get: (): Promise<ApiResponse<UserSettingVO>> =>
    request.get('/auth/user-setting'),

  /** 保存当前用户个人设置 */
  save: (data: UserSettingSaveDTO): Promise<ApiResponse<void>> =>
    request.put('/auth/user-setting', data),
};
