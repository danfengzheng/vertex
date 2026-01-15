import { request } from '../utils/request';
import type { ApiResponse, PageResult, PageQuery } from '../types/api';

/**
 * 角色相关 API
 */

export interface RoleVO {
  id: number;
  name: string;
  code: string;
  description: string;
  status: number;
  createTime: string;
}

export interface RoleCreateDTO {
  name: string;
  code: string;
  description?: string;
  status?: number;
}

export interface RoleUpdateDTO {
  id: number;
  name?: string;
  code?: string;
  description?: string;
  status?: number;
}

export interface RoleQueryDTO extends PageQuery {
  name?: string;
  code?: string;
  status?: number;
}

export const roleApi = {
  /** 根据ID查询角色 */
  getById: (id: number): Promise<ApiResponse<RoleVO>> => {
    return request.get(`/system/role/${id}`);
  },

  /** 分页查询角色 */
  page: (query: RoleQueryDTO): Promise<ApiResponse<PageResult<RoleVO>>> => {
    return request.get('/system/role/page', { params: query });
  },

  /** 查询所有角色 */
  listAll: (): Promise<ApiResponse<RoleVO[]>> => {
    return request.get('/system/role/list');
  },

  /** 创建角色 */
  create: (data: RoleCreateDTO): Promise<ApiResponse<number>> => {
    return request.post('/system/role', data);
  },

  /** 更新角色 */
  update: (data: RoleUpdateDTO): Promise<ApiResponse<void>> => {
    return request.put('/system/role', data);
  },

  /** 删除角色 */
  delete: (id: number): Promise<ApiResponse<void>> => {
    return request.delete(`/system/role/${id}`);
  },
};
