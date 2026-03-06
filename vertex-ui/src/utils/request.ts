import axios from 'axios';
import type { AxiosInstance, AxiosRequestConfig, AxiosResponse } from 'axios';
import { env } from '../config/env';
import { message } from 'antd';
import i18n from '../i18n';

// 创建 axios 实例
const service: AxiosInstance = axios.create({
  baseURL: env.API_BASE_URL + env.API_PREFIX,
  timeout: 60000, // 60s
  headers: {
    'Content-Type': 'application/json',
  },
});

// 请求拦截器
service.interceptors.request.use(
  (config) => {
    // 从 localStorage 获取 token
    const token = localStorage.getItem('token');
    if (token && config.headers) {
      // 确保 "Bearer" 与 token 之间有一个空格，否则后端无法解析
      const prefix = env.TOKEN_PREFIX.endsWith(' ') ? env.TOKEN_PREFIX : env.TOKEN_PREFIX + ' ';
      config.headers[env.TOKEN_HEADER] = prefix + token;
    }
    return config;
  },
  (error) => {
    console.error('Request error:', error);
    return Promise.reject(error);
  }
);

// 是否为登录接口（登录接口返回 401 时不跳转，仅提示错误）
const isLoginRequest = (url: string | undefined) =>
  url != null && (url.includes('/auth/login') || url.endsWith('/auth/login'));

// 响应拦截器
service.interceptors.response.use(
  (response: AxiosResponse) => {
    const res = response.data;

    // 如果返回的状态码不是 200，则判断为错误
    if (res.code !== 200) {
      message.error(res.message || i18n.t('message.common.requestFailed'));

      // 401：未授权时跳转登录页（排除登录接口本身，以及已在登录页时避免死循环）
      if (res.code === 401 && !isLoginRequest(response.config?.url)
          && window.location.pathname !== '/login') {
        localStorage.removeItem('token');
        localStorage.removeItem('user');
        window.location.href = '/login';
      }

      return Promise.reject(new Error(res.message || i18n.t('message.common.requestFailed')));
    }

    return res;
  },
  (error) => {
    const status = error.response?.status;
    const url = error.config?.url;
    if (status === 401 && !isLoginRequest(url) && window.location.pathname !== '/login') {
      localStorage.removeItem('token');
      localStorage.removeItem('user');
      window.location.href = '/login';
      return Promise.reject(error);
    }
    message.error(error.response?.data?.message || error.message || i18n.t('message.common.networkError'));
    return Promise.reject(error);
  }
);

// 封装请求方法
export const request = {
  get<T = any>(url: string, config?: AxiosRequestConfig): Promise<T> {
    return service.get(url, config);
  },
  
  post<T = any>(url: string, data?: any, config?: AxiosRequestConfig): Promise<T> {
    return service.post(url, data, config);
  },
  
  put<T = any>(url: string, data?: any, config?: AxiosRequestConfig): Promise<T> {
    return service.put(url, data, config);
  },
  
  delete<T = any>(url: string, config?: AxiosRequestConfig): Promise<T> {
    return service.delete(url, config);
  },
};

export default service;
