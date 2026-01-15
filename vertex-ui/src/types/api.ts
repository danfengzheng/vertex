/**
 * API 响应基础结构
 */
export interface ApiResponse<T = any> {
  code: number;
  message: string;
  data: T;
  timestamp?: number;
}

/**
 * 分页响应
 */
export interface PageResult<T> {
  total: number;
  records: T[];
}

/**
 * 分页查询参数
 */
export interface PageQuery {
  pageNum: number;
  pageSize: number;
}
