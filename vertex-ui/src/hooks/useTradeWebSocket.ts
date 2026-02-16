import { useEffect, useRef, useCallback } from 'react';
import { Client, IMessage } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import type { OrderVO, PositionVO } from '../api/trading';

/** WebSocket 交易消息类型 */
export interface TradeOrderMessage {
  type: 'ORDER_UPDATE';
  data: OrderVO;
}

export interface TradePositionMessage {
  type: 'POSITION_UPDATE';
  data: PositionVO;
}

export interface TradePendingMessage {
  type: 'PENDING_ORDER';
  data: OrderVO;
}

export type TradeMessage = TradeOrderMessage | TradePositionMessage | TradePendingMessage;

interface UseTradeWebSocketOptions {
  /** WebSocket 端点，默认 /ws */
  endpoint?: string;
  /** 订单更新回调 */
  onOrderUpdate?: (order: OrderVO) => void;
  /** 持仓更新回调 */
  onPositionUpdate?: (position: PositionVO) => void;
  /** 待确认订单回调 */
  onPendingOrder?: (order: OrderVO) => void;
  /** 是否启用，默认 true */
  enabled?: boolean;
}

/**
 * 交易 WebSocket Hook
 * 监听 STOMP 交易主题：
 * - /topic/trade/order     订单状态更新
 * - /topic/trade/position  持仓变动
 * - /topic/trade/pending   待确认订单（MANUAL 模式）
 */
export const useTradeWebSocket = ({
  endpoint = '/ws',
  onOrderUpdate,
  onPositionUpdate,
  onPendingOrder,
  enabled = true,
}: UseTradeWebSocketOptions = {}) => {
  const clientRef = useRef<Client | null>(null);
  const connectedRef = useRef(false);

  const connect = useCallback(() => {
    if (!enabled || clientRef.current?.active) return;

    const baseUrl = import.meta.env.VITE_API_BASE_URL || '';
    const wsUrl = `${baseUrl}${endpoint}`;

    const client = new Client({
      webSocketFactory: () => new SockJS(wsUrl),
      reconnectDelay: 5000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      onConnect: () => {
        connectedRef.current = true;

        // 订阅订单更新
        client.subscribe('/topic/trade/order', (msg: IMessage) => {
          try {
            const order: OrderVO = JSON.parse(msg.body);
            onOrderUpdate?.(order);
          } catch {
            // ignore parse error
          }
        });

        // 订阅持仓更新
        client.subscribe('/topic/trade/position', (msg: IMessage) => {
          try {
            const position: PositionVO = JSON.parse(msg.body);
            onPositionUpdate?.(position);
          } catch {
            // ignore parse error
          }
        });

        // 订阅待确认订单
        client.subscribe('/topic/trade/pending', (msg: IMessage) => {
          try {
            const order: OrderVO = JSON.parse(msg.body);
            onPendingOrder?.(order);
          } catch {
            // ignore parse error
          }
        });
      },
      onDisconnect: () => {
        connectedRef.current = false;
      },
      onStompError: (frame) => {
        console.error('Trade STOMP error:', frame.headers['message']);
        connectedRef.current = false;
      },
    });

    client.activate();
    clientRef.current = client;
  }, [enabled, endpoint, onOrderUpdate, onPositionUpdate, onPendingOrder]);

  const disconnect = useCallback(() => {
    if (clientRef.current?.active) {
      clientRef.current.deactivate();
    }
    clientRef.current = null;
    connectedRef.current = false;
  }, []);

  useEffect(() => {
    connect();
    return () => {
      disconnect();
    };
  }, [connect, disconnect]);

  return {
    connected: connectedRef.current,
    disconnect,
    reconnect: connect,
  };
};
