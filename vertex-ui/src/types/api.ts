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
 * <p>
 * 高频表（如 stg_signal）后端跳过 COUNT(*)，此时 total 为 null，
 * 前端应改用 hasNext 判定"是否有下一页"。传统小表仍返回 total。
 * </p>
 */
export interface PageResult<T> {
  total: number | null;
  hasNext?: boolean | null;
  records: T[];
}

/**
 * 分页查询参数
 */
export interface PageQuery {
  pageNum: number;
  pageSize: number;
}
