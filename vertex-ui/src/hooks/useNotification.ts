import { useContext } from 'react';
import { NotificationContext } from '../contexts/NotificationContext';
import type { NotificationContextValue } from '../contexts/NotificationContext';

/**
 * 消费全局通知上下文的 Hook
 *
 * 必须在 NotificationProvider 内使用
 */
export const useNotification = (): NotificationContextValue => {
  const context = useContext(NotificationContext);
  if (!context) {
    throw new Error('useNotification must be used within a NotificationProvider');
  }
  return context;
};
