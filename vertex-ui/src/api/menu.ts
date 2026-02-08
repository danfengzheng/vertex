import { request } from '../utils/request';
import type { ApiResponse, PageResult, PageQuery } from '../types/api';

/**
 * 菜单相关 API
 */

export interface MenuVO {
  id: string;
  parentId: string | null;
  name: string;
  i18nKey: string;
  path: string;
  component: string;
  icon: string;
  type: number;
  permission: string;
  sort: number;
  status: number;
  createTime: string;
  children?: MenuVO[];
}

export interface MenuCreateDTO {
  parentId?: string;
  name: string;
  i18nKey?: string;
  path?: string;
  component?: string;
  icon?: string;
  type: number;
  permission?: string;
  sort?: number;
  status?: number;
}

export interface MenuUpdateDTO {
  id: string;
  parentId?: string;
  name?: string;
  i18nKey?: string;
  path?: string;
  component?: string;
  icon?: string;
  type?: number;
  permission?: string;
  sort?: number;
  status?: number;
}

export interface MenuQueryDTO extends PageQuery {
  name?: string;
  parentId?: string;
  type?: number;
  status?: number;
}

export const menuApi = {
  /** 根据ID查询菜单 */
  getById: (id: string): Promise<ApiResponse<MenuVO>> => {
    return request.get(`/system/menu/${id}`);
  },

  /** 分页查询菜单 */
  page: (query: MenuQueryDTO): Promise<ApiResponse<PageResult<MenuVO>>> => {
    return request.get('/system/menu/page', { params: query });
  },

  /** 查询所有菜单（树形结构） */
  listTree: (): Promise<ApiResponse<MenuVO[]>> => {
    return request.get('/system/menu/tree');
  },

  /** 根据父ID查询子菜单 */
  listByParentId: (parentId: string): Promise<ApiResponse<MenuVO[]>> => {
    return request.get(`/system/menu/parent/${parentId}`);
  },

  /** 创建菜单 */
  create: (data: MenuCreateDTO): Promise<ApiResponse<string>> => {
    return request.post('/system/menu', data);
  },

  /** 更新菜单 */
  update: (data: MenuUpdateDTO): Promise<ApiResponse<void>> => {
    return request.put('/system/menu', data);
  },

  /** 删除菜单 */
  delete: (id: string): Promise<ApiResponse<void>> => {
    return request.delete(`/system/menu/${id}`);
  },
};
