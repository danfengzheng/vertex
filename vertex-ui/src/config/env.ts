/**
 * 环境配置
 */
export const env = {
  /** 应用标题 */
  APP_TITLE: import.meta.env.VITE_APP_TITLE || 'Vertex管理系统',
  
  /** API 基础地址 */
  API_BASE_URL: import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080',
  
  /** API 前缀 */
  API_PREFIX: import.meta.env.VITE_API_PREFIX || '/admin',
  
  /** Token 请求头 */
  TOKEN_HEADER: import.meta.env.VITE_TOKEN_HEADER || 'Authorization',
  
  /** Token 前缀 */
  TOKEN_PREFIX: import.meta.env.VITE_TOKEN_PREFIX || 'Bearer ',
  
  /** 当前环境 */
  MODE: import.meta.env.MODE,
  
  /** 是否为开发环境 */
  isDev: import.meta.env.DEV,
  
  /** 是否为生产环境 */
  isProd: import.meta.env.PROD,
} as const;
