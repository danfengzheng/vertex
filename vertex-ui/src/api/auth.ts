import { request } from '../utils/request';
import type { ApiResponse } from '../types/api';
import type { UserVO } from './user';

/**
 * 认证相关 API
 */

export interface LoginDTO {
  username: string;
  password: string;
}

export interface LoginVO {
  token: string;
  user: UserVO;
}

export const authApi = {
  /** 登录 */
  login: (data: LoginDTO): Promise<ApiResponse<LoginVO>> =>
    request.post('/auth/login', data),

  /** 获取当前用户信息 */
  getMe: (): Promise<ApiResponse<UserVO>> =>
    request.get('/auth/me'),
};
