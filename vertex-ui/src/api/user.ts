import { request } from '../utils/request';
import type { ApiResponse, PageResult, PageQuery } from '../types/api';

/**
 * 用户相关 API
 */

export interface UserVO {
  id: number;
  username: string;
  nickname: string;
  phone: string;
  email: string;
  avatar: string;
  gender: number;
  accountType: number;
  status: number;
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
  id: number;
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
  getById: (id: number): Promise<ApiResponse<UserVO>> => {
    return request.get(`/system/user/${id}`);
  },

  /** 分页查询用户 */
  page: (query: UserQueryDTO): Promise<ApiResponse<PageResult<UserVO>>> => {
    return request.get('/system/user/page', { params: query });
  },

  /** 创建用户 */
  create: (data: UserCreateDTO): Promise<ApiResponse<number>> => {
    return request.post('/system/user', data);
  },

  /** 更新用户 */
  update: (data: UserUpdateDTO): Promise<ApiResponse<void>> => {
    return request.put('/system/user', data);
  },

  /** 删除用户 */
  delete: (id: number): Promise<ApiResponse<void>> => {
    return request.delete(`/user/${id}`);
  },
};
