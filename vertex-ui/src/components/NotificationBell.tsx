import { useState } from 'react';
import { Badge, Popover } from 'antd';
import { BellOutlined } from '@ant-design/icons';
import { useNotification } from '../hooks/useNotification';
import { NotificationPanel } from './NotificationPanel';

/**
 * Header 通知铃铛组件
 *
 * 显示未读计数 Badge + 点击展开通知面板
 */
export const NotificationBell = () => {
  const { unreadCount } = useNotification();
  const [open, setOpen] = useState(false);

  return (
    <Popover
      content={<NotificationPanel onClose={() => setOpen(false)} />}
      trigger="click"
      open={open}
      onOpenChange={setOpen}
      placement="bottomRight"
      arrow={false}
      overlayInnerStyle={{ padding: '12px 16px' }}
    >
      <Badge count={unreadCount} size="small" offset={[-2, 2]}>
        <BellOutlined style={{ fontSize: 18, cursor: 'pointer' }} />
      </Badge>
    </Popover>
  );
};
