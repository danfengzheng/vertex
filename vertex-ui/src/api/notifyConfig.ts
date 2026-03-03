import { request } from '../utils/request';
import type { ApiResponse } from '../types/api';

/**
 * 用户通知配置 API
 */

export interface NotifyConfigVO {
  channel: string;
  chatId: string | null;
  enabled: number;
}

export interface NotifyConfigSaveDTO {
  chatId: string;
  enabled: number;
}

export const notifyConfigApi = {
  /** 获取当前用户 Telegram 通知配置 */
  get: (): Promise<ApiResponse<NotifyConfigVO>> =>
    request.get('/auth/notify-config'),

  /** 保存当前用户 Telegram 通知配置 */
  save: (data: NotifyConfigSaveDTO): Promise<ApiResponse<void>> =>
    request.put('/auth/notify-config', data),
};
