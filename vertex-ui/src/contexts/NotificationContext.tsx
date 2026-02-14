import React, { createContext, useState, useCallback, useEffect, useRef, useMemo } from 'react';
import { notification } from 'antd';
import { useTranslation } from 'react-i18next';
import { useSignalWebSocket } from '../hooks/useSignalWebSocket';
import type { SignalVO } from '../api/strategy';
import type { AppNotification } from '../types/notification';

const STORAGE_KEY = 'vertex_read_notification_ids';
const MAX_NOTIFICATIONS = 50;
const MAX_STORED_READ_IDS = 200;

export interface NotificationContextValue {
  /** 所有通知（最新在前），上限 MAX_NOTIFICATIONS */
  notifications: AppNotification[];
  /** 未读通知数量 */
  unreadCount: number;
  /** WebSocket 连接状态 */
  connected: boolean;
  /** 标记单条已读 */
  markAsRead: (id: string) => void;
  /** 全部标记已读 */
  markAllAsRead: () => void;
  /** 清空所有通知 */
  clearAll: () => void;
}

export const NotificationContext = createContext<NotificationContextValue | null>(null);

/** 从 localStorage 加载已读 ID 集合 */
const loadReadIds = (): Set<string> => {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    return stored ? new Set(JSON.parse(stored)) : new Set();
  } catch {
    return new Set();
  }
};

/** 持久化已读 ID 到 localStorage */
const persistReadIds = (ids: Set<string>) => {
  try {
    const arr = Array.from(ids);
    // 超出上限时只保留最新的
    const trimmed = arr.length > MAX_STORED_READ_IDS
      ? arr.slice(arr.length - MAX_STORED_READ_IDS)
      : arr;
    localStorage.setItem(STORAGE_KEY, JSON.stringify(trimmed));
  } catch {
    // localStorage 不可用时静默失败
  }
};

export const NotificationProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { t } = useTranslation();
  const [notifications, setNotifications] = useState<AppNotification[]>([]);
  const [readIds, setReadIds] = useState<Set<string>>(loadReadIds);

  // 用 ref 维护 readIds 的最新引用，避免 handleSignal 闭包问题
  const readIdsRef = useRef(readIds);
  useEffect(() => {
    readIdsRef.current = readIds;
  }, [readIds]);

  // readIds 变化时持久化
  useEffect(() => {
    persistReadIds(readIds);
  }, [readIds]);

  /** 处理新信号 */
  const handleSignal = useCallback((signal: SignalVO) => {
    const id = String(signal.id);

    const newNotification: AppNotification = {
      id,
      signal,
      read: readIdsRef.current.has(id),
      receivedAt: Date.now(),
    };

    setNotifications((prev) => [newNotification, ...prev].slice(0, MAX_NOTIFICATIONS));

    // BUY/SELL 信号弹出全局 toast 通知
    if (signal.signalType !== 'NEUTRAL') {
      notification.open({
        message: signal.signalType === 'BUY'
          ? t('notification.buySignal')
          : t('notification.sellSignal'),
        description: `${signal.strategyName}: ${signal.symbol} @ ${signal.price}`,
        type: signal.signalType === 'BUY' ? 'success' : 'warning',
        duration: 6,
        placement: 'topRight',
      });
    }
  }, [t]);

  const { connected } = useSignalWebSocket({
    onSignal: handleSignal,
    enabled: true,
  });

  /** 标记单条已读 */
  const markAsRead = useCallback((id: string) => {
    setReadIds((prev) => {
      const next = new Set(prev);
      next.add(id);
      return next;
    });
    setNotifications((prev) =>
      prev.map((n) => (n.id === id ? { ...n, read: true } : n)),
    );
  }, []);

  /** 全部标记已读 */
  const markAllAsRead = useCallback(() => {
    setNotifications((prev) => {
      const ids = prev.map((n) => n.id);
      setReadIds((prevIds) => {
        const next = new Set(prevIds);
        ids.forEach((id) => next.add(id));
        return next;
      });
      return prev.map((n) => ({ ...n, read: true }));
    });
  }, []);

  /** 清空所有通知 */
  const clearAll = useCallback(() => {
    setNotifications([]);
  }, []);

  const unreadCount = useMemo(
    () => notifications.filter((n) => !n.read).length,
    [notifications],
  );

  const value = useMemo<NotificationContextValue>(
    () => ({
      notifications,
      unreadCount,
      connected,
      markAsRead,
      markAllAsRead,
      clearAll,
    }),
    [notifications, unreadCount, connected, markAsRead, markAllAsRead, clearAll],
  );

  return (
    <NotificationContext.Provider value={value}>
      {children}
    </NotificationContext.Provider>
  );
};
