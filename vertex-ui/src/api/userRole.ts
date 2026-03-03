import { request } from '../utils/request';
import type { ApiResponse } from '../types/api';

/**
 * 用户角色关联 API
 */
export const userRoleApi = {
  /** 查询用户已分配的角色ID列表 */
  getRoleIdsByUserId: (userId: string): Promise<ApiResponse<string[]>> =>
    request.get(`/system/user-role/user/${userId}`),

  /** 为用户分配角色（全量替换） */
  assignRolesToUser: (userId: string, roleIds: string[]): Promise<ApiResponse<void>> =>
    request.post(`/system/user-role/assign?userId=${userId}`, roleIds),
};
