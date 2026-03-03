import { request } from '../utils/request';
import type { ApiResponse } from '../types/api';

/**
 * 角色菜单关联 API
 */
export const roleMenuApi = {
  /** 查询角色已分配的菜单ID列表 */
  getMenuIdsByRoleId: (roleId: string): Promise<ApiResponse<string[]>> =>
    request.get(`/system/role-menu/role/${roleId}`),

  /** 为角色分配菜单（全量替换） */
  assignMenusToRole: (roleId: string, menuIds: string[]): Promise<ApiResponse<void>> =>
    request.post(`/system/role-menu/assign?roleId=${roleId}`, menuIds),
};
