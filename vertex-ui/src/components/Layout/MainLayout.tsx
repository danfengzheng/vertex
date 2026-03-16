import { useState, useEffect, useMemo } from 'react';
import { Layout, Menu, Avatar, Dropdown, Space, theme, Modal, Form, Input, Switch, message } from 'antd';
import { useNavigate, useLocation, Outlet } from 'react-router-dom';
import { notifyConfigApi, NotifyConfigVO } from '../../api/notifyConfig';
import { usePermission } from '../../contexts/PermissionContext';
import {
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  UserOutlined,
  SettingOutlined,
  LogoutOutlined,
  MenuOutlined,
  SafetyOutlined,
  AppstoreOutlined,
  StockOutlined,
  ApiOutlined,
  LineChartOutlined,
  FundOutlined,
  ThunderboltOutlined,
  BookOutlined,
  SwapOutlined,
  BankOutlined,
  OrderedListOutlined,
  DashboardOutlined,
  PieChartOutlined,
  NodeExpandOutlined,
  BellOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { LanguageSwitcher } from '../LanguageSwitcher';
import { NotificationBell } from '../NotificationBell';
import type { MenuProps } from 'antd';

const { Header, Sider, Content } = Layout;

export const MainLayout = () => {
  const [collapsed, setCollapsed] = useState(false);
  const { t } = useTranslation();
  const navigate = useNavigate();
  const location = useLocation();
  const {
    token: { colorBgContainer, borderRadiusLG },
  } = theme.useToken();

  // 获取当前打开的菜单（根据路径判断）
  const getOpenKeys = () => {
    if (location.pathname.startsWith('/user') ||
        location.pathname.startsWith('/menu') ||
        location.pathname.startsWith('/role')) {
      return ['system'];
    }
    if (location.pathname.startsWith('/quote')) {
      return ['quote'];
    }
    if (location.pathname.startsWith('/strategy')) {
      return ['strategy'];
    }
    if (location.pathname.startsWith('/trading')) {
      return ['trading'];
    }
    if (location.pathname.startsWith('/chain')) {
      return ['chain'];
    }
    return [];
  };

  const [openKeys, setOpenKeys] = useState<string[]>(getOpenKeys());
  // 来自 PermissionContext（已在 App 层统一加载）
  const { allowedPaths } = usePermission();

  // 从当前登录用户信息中读取用户名
  const currentUser = useMemo(() => {
    try { return JSON.parse(localStorage.getItem('user') || '{}'); } catch { return {}; }
  }, []);

  // ─── 通知设置弹窗 ────────────────────────────────
  const [notifyModalVisible, setNotifyModalVisible] = useState(false);
  const [notifyConfig, setNotifyConfig] = useState<NotifyConfigVO | null>(null);
  const [notifySaving, setNotifySaving] = useState(false);
  const [notifyForm] = Form.useForm();

  const handleOpenNotifyModal = async () => {
    setNotifyModalVisible(true);
    try {
      const res = await notifyConfigApi.get();
      if (res.code === 200) {
        setNotifyConfig(res.data);
        notifyForm.setFieldsValue({
          chatId: res.data.chatId ?? '',
          enabled: res.data.enabled === 1,
        });
      }
    } catch {
      // ignore
    }
  };

  const handleSaveNotifyConfig = async () => {
    try {
      const values = await notifyForm.validateFields();
      setNotifySaving(true);
      await notifyConfigApi.save({ chatId: values.chatId, enabled: values.enabled ? 1 : 0 });
      message.success('通知配置已保存');
      setNotifyModalVisible(false);
    } catch {
      // validation or network error
    } finally {
      setNotifySaving(false);
    }
  };

  // 当路径变化时，自动展开对应的父菜单
  useEffect(() => {
    setOpenKeys(getOpenKeys());
  }, [location.pathname]);

  // 菜单项配置
  const menuItems: MenuProps['items'] = [
    {
      key: 'system',
      icon: <AppstoreOutlined />,
      label: t('text.system.title'),
      children: [
        {
          key: '/user',
          icon: <UserOutlined />,
          label: t('text.system.user'),
        },
        {
          key: '/menu',
          icon: <MenuOutlined />,
          label: t('text.system.menu'),
        },
        {
          key: '/role',
          icon: <SafetyOutlined />,
          label: t('text.system.role'),
        },
      ],
    },
    {
      key: 'quote',
      icon: <StockOutlined />,
      label: t('text.quote.title'),
      children: [
        {
          key: '/quote/source',
          icon: <ApiOutlined />,
          label: t('text.quote.sourceTitle'),
        },
        {
          key: '/quote/kline',
          icon: <LineChartOutlined />,
          label: t('text.quote.klineTitle'),
        },
      ],
    },
    {
      key: 'strategy',
      icon: <FundOutlined />,
      label: t('text.strategy.title'),
      children: [
        {
          key: '/strategy/config',
          icon: <SettingOutlined />,
          label: t('text.strategy.configTitle'),
        },
        {
          key: '/strategy/signals',
          icon: <ThunderboltOutlined />,
          label: t('text.strategy.signalTitle'),
        },
      ],
    },
    {
      key: 'trading',
      icon: <SwapOutlined />,
      label: t('text.trading.title'),
      children: [
        {
          key: '/trading/accounts',
          icon: <BankOutlined />,
          label: t('text.trading.accountTitle'),
        },
        {
          key: '/trading/orders',
          icon: <OrderedListOutlined />,
          label: t('text.trading.orderTitle'),
        },
        {
          key: '/trading/positions',
          icon: <DashboardOutlined />,
          label: t('text.trading.positionTitle'),
        },
        {
          key: '/trading/pnl',
          icon: <PieChartOutlined />,
          label: t('text.trading.pnlAnalysisTitle'),
        },
      ],
    },
    {
      key: 'chain',
      icon: <NodeExpandOutlined />,
      label: t('text.chain.title'),
      children: [
        {
          key: '/chain/tokens',
          icon: <FundOutlined />,
          label: t('text.chain.tokenListTitle'),
        },
        {
          key: '/chain/alerts',
          icon: <BellOutlined />,
          label: t('text.chain.alertRuleTitle'),
        },
        {
          key: '/chain/source-config',
          icon: <SettingOutlined />,
          label: t('text.chain.sourceConfigTitle'),
        },
      ],
    },
    {
      key: '/guide/strategy',
      icon: <BookOutlined />,
      label: t('text.guide.title'),
    },
  ];

  /** 根据允许路径递归过滤菜单项（叶节点按 key 匹配，父节点有子项则保留） */
  const filterMenuItems = (items: MenuProps['items'], allowed: Set<string>): MenuProps['items'] => {
    if (!items) return items;
    return items.reduce<NonNullable<MenuProps['items']>>((acc, item) => {
      if (!item) return acc;
      const i = item as any;
      if (i.children) {
        const filtered = filterMenuItems(i.children, allowed);
        if (filtered && filtered.length > 0) acc.push({ ...i, children: filtered });
      } else if (allowed.has(i.key as string)) {
        acc.push(i);
      }
      return acc;
    }, []);
  };

  const displayedMenuItems = allowedPaths ? filterMenuItems(menuItems, allowedPaths) : menuItems;

  // 用户下拉菜单
  const userMenuItems: MenuProps['items'] = [
    {
      key: 'notify',
      icon: <BellOutlined />,
      label: '通知设置',
    },
    {
      type: 'divider',
    },
    {
      key: 'logout',
      icon: <LogoutOutlined />,
      label: t('text.user.logout'),
      danger: true,
    },
  ];

  const handleMenuClick = ({ key }: { key: string }) => {
    navigate(key);
  };

  const handleUserMenuClick = ({ key }: { key: string }) => {
    if (key === 'logout') {
      localStorage.removeItem('token');
      navigate('/login');
    } else if (key === 'notify') {
      handleOpenNotifyModal();
    }
  };

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider
        trigger={null}
        collapsible
        collapsed={collapsed}
        style={{
          overflow: 'auto',
          height: '100vh',
          position: 'fixed',
          left: 0,
          top: 0,
          bottom: 0,
        }}
        theme="dark"
      >
        <div
          style={{
            height: 64,
            margin: 16,
            display: 'flex',
            alignItems: 'center',
            justifyContent: collapsed ? 'center' : 'flex-start',
            color: '#fff',
            fontSize: collapsed ? 20 : 18,
            fontWeight: 'bold',
          }}
        >
          {collapsed ? 'V' : 'Vertex'}
        </div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[location.pathname]}
          openKeys={openKeys}
          onOpenChange={setOpenKeys}
          items={displayedMenuItems}
          onClick={handleMenuClick}
        />
      </Sider>
      <Layout style={{ marginLeft: collapsed ? 80 : 200, transition: 'margin-left 0.2s' }}>
        <Header
          style={{
            padding: '0 24px',
            background: colorBgContainer,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            boxShadow: '0 2px 8px rgba(0,0,0,0.1)',
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
            {collapsed ? (
              <MenuUnfoldOutlined
                style={{ fontSize: 18, cursor: 'pointer' }}
                onClick={() => setCollapsed(!collapsed)}
              />
            ) : (
              <MenuFoldOutlined
                style={{ fontSize: 18, cursor: 'pointer' }}
                onClick={() => setCollapsed(!collapsed)}
              />
            )}
          </div>
          <Space size="middle">
            <LanguageSwitcher />
            <NotificationBell />
            <Dropdown
              menu={{
                items: userMenuItems,
                onClick: handleUserMenuClick,
              }}
              placement="bottomRight"
            >
              <Space style={{ cursor: 'pointer' }}>
                <Avatar icon={<UserOutlined />} />
                <span>{currentUser.nickname || currentUser.username || t('text.user.admin')}</span>
              </Space>
            </Dropdown>
          </Space>
        </Header>
        <Content
          style={{
            margin: '24px 16px',
            padding: 24,
            minHeight: 280,
            background: colorBgContainer,
            borderRadius: borderRadiusLG,
          }}
        >
          <Outlet />
        </Content>
      </Layout>

      {/* Telegram 通知设置弹窗 */}
      <Modal
        title="Telegram 通知设置"
        open={notifyModalVisible}
        onOk={handleSaveNotifyConfig}
        confirmLoading={notifySaving}
        onCancel={() => setNotifyModalVisible(false)}
      >
        <Form form={notifyForm} layout="vertical" style={{ marginTop: 8 }}>
          <Form.Item
            name="chatId"
            label="Chat ID"
            extra="填写你的 Telegram 个人 Chat ID 或群组 Chat ID（负数）。信号触发时，通知将发送到此 ID。留空则使用系统全局配置。"
          >
            <Input placeholder="如：123456789 或 -1001234567890" />
          </Form.Item>
          <Form.Item name="enabled" label="启用通知" valuePropName="checked">
            <Switch checkedChildren="开" unCheckedChildren="关" />
          </Form.Item>
        </Form>
        {notifyConfig === null && (
          <div style={{ color: '#999', fontSize: 12 }}>加载中...</div>
        )}
      </Modal>
    </Layout>
  );
};
