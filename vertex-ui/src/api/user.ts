import { request } from '../utils/request';
import type { ApiResponse, PageResult, PageQuery } from '../types/api';

/**
 * 用户相关 API
 */

export interface UserVO {
  id: string;
  username: string;
  nickname: string;
  phone: string;
  email: string;
  avatar: string;
  gender: number;
  accountType: number;
  status: number;
  /** 是否已冻结（连续登录失败 5 次） */
  locked?: boolean;
  createTime: string;
}

export interface UserCreateDTO {
  username: string;
  password: string;
  phone: string;
  email?: string;
  nickname?: string;
  gender?: number;
  accountType?: number;
}

export interface UserUpdateDTO {
  id: string;
  username?: string;
  password?: string;
  phone?: string;
  email?: string;
  nickname?: string;
  avatar?: string;
  gender?: number;
  accountType?: number;
  status?: number;
}

export interface UserQueryDTO extends PageQuery {
  username?: string;
  phone?: string;
  accountType?: number;
  status?: number;
  startTime?: string;
  endTime?: string;
}

export const userApi = {
  /** 根据ID查询用户 */
  getById: (id: string): Promise<ApiResponse<UserVO>> => {
    return request.get(`/system/user/${id}`);
  },

  /** 分页查询用户 */
  page: (query: UserQueryDTO): Promise<ApiResponse<PageResult<UserVO>>> => {
    return request.get('/system/user/page', { params: query });
  },

  /** 创建用户 */
  create: (data: UserCreateDTO): Promise<ApiResponse<string>> => {
    return request.post('/system/user', data);
  },

  /** 更新用户 */
  update: (data: UserUpdateDTO): Promise<ApiResponse<void>> => {
    return request.put('/system/user', data);
  },

  /** 删除用户 */
  delete: (id: string): Promise<ApiResponse<void>> => {
    return request.delete(`/system/user/${id}`);
  },

  /** 解冻账户（连续密码错误 5 次冻结后由管理员解冻） */
  unfreeze: (id: string): Promise<ApiResponse<void>> => {
    return request.put(`/system/user/${id}/unfreeze`);
  },
};
