import axios from 'axios';
import type { AxiosInstance, AxiosRequestConfig, AxiosResponse } from 'axios';
import { env } from '../config/env';
import { message } from 'antd';
import i18n from '../i18n';

// 创建 axios 实例
const service: AxiosInstance = axios.create({
  baseURL: env.API_BASE_URL + env.API_PREFIX,
  timeout: 10000,
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
      // 使用配置的 token 前缀和请求头
      config.headers[env.TOKEN_HEADER] = env.TOKEN_PREFIX + token;
    }
    return config;
  },
  (error) => {
    console.error('Request error:', error);
    return Promise.reject(error);
  }
);

// 响应拦截器
service.interceptors.response.use(
  (response: AxiosResponse) => {
    const res = response.data;
    
    // 如果返回的状态码不是 200，则判断为错误
    if (res.code !== 200) {
      message.error(res.message || i18n.t('message.common.requestFailed'));
      
      // 401: 未授权，跳转到登录页
      if (res.code === 401) {
        localStorage.removeItem('token');
        window.location.href = '/login';
      }
      
      return Promise.reject(new Error(res.message || i18n.t('message.common.requestFailed')));
    }
    
    return res;
  },
  (error) => {
    console.error('Response error:', error);
    message.error(error.message || i18n.t('message.common.networkError'));
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
