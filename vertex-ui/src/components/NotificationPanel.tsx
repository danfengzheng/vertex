import { List, Tag, Button, Space, Typography, Empty, Badge } from 'antd';
import { CheckOutlined, DeleteOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import 'dayjs/locale/zh-cn';
import { useNotification } from '../hooks/useNotification';
import type { AppNotification } from '../types/notification';

dayjs.extend(relativeTime);

interface NotificationPanelProps {
  onClose: () => void;
}

const signalTypeColor: Record<string, string> = {
  BUY: 'success',
  SELL: 'error',
  NEUTRAL: 'default',
};

const signalTypeLabel: Record<string, string> = {
  BUY: '买入',
  SELL: '卖出',
  NEUTRAL: '中性',
};

export const NotificationPanel = ({ onClose }: NotificationPanelProps) => {
  const { t, i18n } = useTranslation();
  const navigate = useNavigate();
  const { notifications, unreadCount, markAsRead, markAllAsRead, clearAll } = useNotification();

  const handleClick = (item: AppNotification) => {
    markAsRead(item.id);
    navigate('/strategy/signals');
    onClose();
  };

  // dayjs locale 根据当前语言切换
  const locale = i18n.language === 'zh-CN' ? 'zh-cn' : 'en';

  return (
    <div style={{ width: 350 }}>
      {/* 头部 */}
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          padding: '4px 0 8px',
          borderBottom: '1px solid #f0f0f0',
        }}
      >
        <Typography.Text strong>
          {t('notification.title')}
          {unreadCount > 0 && (
            <span style={{ color: '#1677ff', marginLeft: 4 }}>({unreadCount})</span>
          )}
        </Typography.Text>
        <Space size="small">
          {unreadCount > 0 && (
            <Button
              type="link"
              size="small"
              icon={<CheckOutlined />}
              onClick={markAllAsRead}
            >
              {t('notification.markAllRead')}
            </Button>
          )}
          {notifications.length > 0 && (
            <Button
              type="link"
              size="small"
              icon={<DeleteOutlined />}
              onClick={clearAll}
            >
              {t('notification.clearAll')}
            </Button>
          )}
        </Space>
      </div>

      {/* 通知列表 */}
      <div style={{ maxHeight: 420, overflowY: 'auto' }}>
        {notifications.length === 0 ? (
          <Empty
            description={t('notification.empty')}
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            style={{ padding: '32px 0' }}
          />
        ) : (
          <List
            dataSource={notifications}
            split={false}
            renderItem={(item) => (
              <List.Item
                onClick={() => handleClick(item)}
                style={{
                  cursor: 'pointer',
                  backgroundColor: item.read ? 'transparent' : 'rgba(22, 119, 255, 0.04)',
                  padding: '10px 8px',
                  borderRadius: 6,
                  marginTop: 2,
                  transition: 'background-color 0.2s',
                }}
                onMouseEnter={(e) => {
                  (e.currentTarget as HTMLElement).style.backgroundColor = 'rgba(0,0,0,0.04)';
                }}
                onMouseLeave={(e) => {
                  (e.currentTarget as HTMLElement).style.backgroundColor = item.read
                    ? 'transparent'
                    : 'rgba(22, 119, 255, 0.04)';
                }}
              >
                <div style={{ display: 'flex', alignItems: 'flex-start', gap: 8, width: '100%' }}>
                  {/* 未读圆点 */}
                  <div style={{ paddingTop: 6, width: 8, flexShrink: 0 }}>
                    {!item.read && <Badge status="processing" />}
                  </div>

                  {/* 内容 */}
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 4 }}>
                      <Tag
                        color={signalTypeColor[item.signal.signalType]}
                        style={{ marginRight: 0 }}
                      >
                        {signalTypeLabel[item.signal.signalType]}
                      </Tag>
                      <Typography.Text
                        strong
                        style={{ fontSize: 13 }}
                        ellipsis
                      >
                        {item.signal.strategyName}
                      </Typography.Text>
                    </div>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                        {item.signal.symbol} @ {item.signal.price}
                      </Typography.Text>
                      <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                        {dayjs(item.receivedAt).locale(locale).fromNow()}
                      </Typography.Text>
                    </div>
                  </div>
                </div>
              </List.Item>
            )}
          />
        )}
      </div>
    </div>
  );
};
