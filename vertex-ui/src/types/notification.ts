import { SignalVO } from '../api/strategy';

/** 站内通知实体 */
export interface AppNotification {
  /** 唯一 ID，复用 signal.id */
  id: string;
  /** 原始信号数据 */
  signal: SignalVO;
  /** 已读/未读 */
  read: boolean;
  /** 客户端接收时间戳 (ms) */
  receivedAt: number;
}
