import { useEffect, useRef, useState, useCallback } from 'react';
import { SignalVO } from '../api/strategy';

interface UseSignalWebSocketOptions {
  /** 新信号回调 */
  onSignal: (signal: SignalVO) => void;
  /** 是否启用 */
  enabled?: boolean;
}

interface UseSignalWebSocketReturn {
  /** WebSocket 连接状态 */
  connected: boolean;
  /** 手动断开 */
  disconnect: () => void;
}

/**
 * 信号 WebSocket Hook
 * <p>
 * 使用原生 WebSocket + SockJS 协议连接后端 STOMP endpoint，
 * 简化实现不依赖 @stomp/stompjs 等第三方库。
 * 采用简易 STOMP 帧解析订阅 /topic/signal。
 * </p>
 */
export const useSignalWebSocket = ({
  onSignal,
  enabled = true,
}: UseSignalWebSocketOptions): UseSignalWebSocketReturn => {
  const [connected, setConnected] = useState(false);
  const wsRef = useRef<WebSocket | null>(null);
  const reconnectTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const onSignalRef = useRef(onSignal);

  // 保持 onSignal 回调最新
  useEffect(() => {
    onSignalRef.current = onSignal;
  }, [onSignal]);

  const connect = useCallback(() => {
    if (!enabled) return;

    try {
      // SockJS 兼容的 WebSocket URL
      const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
      const host = window.location.host;
      const wsUrl = `${protocol}//${host}/api/ws/signal/websocket`;

      const ws = new WebSocket(wsUrl);
      wsRef.current = ws;

      ws.onopen = () => {
        // 发送 STOMP CONNECT 帧
        ws.send('CONNECT\naccept-version:1.1,1.0\nheart-beat:10000,10000\n\n\0');
      };

      ws.onmessage = (event) => {
        const data = typeof event.data === 'string' ? event.data : '';

        if (data.startsWith('CONNECTED')) {
          setConnected(true);
          // 订阅信号主题
          ws.send('SUBSCRIBE\nid:sub-signal\ndestination:/topic/signal\n\n\0');
        } else if (data.startsWith('MESSAGE')) {
          // 解析 STOMP MESSAGE 帧
          const bodyStart = data.indexOf('\n\n');
          if (bodyStart !== -1) {
            const body = data.substring(bodyStart + 2).replace(/\0$/, '');
            try {
              const signal: SignalVO = JSON.parse(body);
              onSignalRef.current(signal);
            } catch (e) {
              console.warn('Failed to parse signal message:', e);
            }
          }
        }
      };

      ws.onclose = () => {
        setConnected(false);
        wsRef.current = null;
        // 自动重连
        if (enabled) {
          reconnectTimerRef.current = setTimeout(connect, 5000);
        }
      };

      ws.onerror = () => {
        ws.close();
      };
    } catch (e) {
      console.warn('WebSocket connection failed:', e);
      // 重连
      if (enabled) {
        reconnectTimerRef.current = setTimeout(connect, 5000);
      }
    }
  }, [enabled]);

  const disconnect = useCallback(() => {
    if (reconnectTimerRef.current) {
      clearTimeout(reconnectTimerRef.current);
      reconnectTimerRef.current = null;
    }
    if (wsRef.current) {
      try {
        wsRef.current.send('DISCONNECT\n\n\0');
      } catch {
        // ignore
      }
      wsRef.current.close();
      wsRef.current = null;
    }
    setConnected(false);
  }, []);

  useEffect(() => {
    if (enabled) {
      connect();
    }
    return () => {
      disconnect();
    };
  }, [enabled, connect, disconnect]);

  return { connected, disconnect };
};
