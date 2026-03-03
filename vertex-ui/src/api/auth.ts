import { request } from '../utils/request';
import type { ApiResponse } from '../types/api';
import type { UserVO } from './user';
import type { MenuVO } from './menu';

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

  /** 获取当前用户可访问的菜单（管理员返回全部，普通用户按角色过滤） */
  getUserMenus: (): Promise<ApiResponse<MenuVO[]>> =>
    request.get('/auth/menus'),
};
