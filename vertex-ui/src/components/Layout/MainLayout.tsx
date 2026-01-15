import { useState, useEffect } from 'react';
import { Layout, Menu, Avatar, Dropdown, Space, theme } from 'antd';
import { useNavigate, useLocation } from 'react-router-dom';
import {
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  UserOutlined,
  SettingOutlined,
  LogoutOutlined,
  MenuOutlined,
  SafetyOutlined,
  AppstoreOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { LanguageSwitcher } from '../LanguageSwitcher';
import type { MenuProps } from 'antd';

const { Header, Sider, Content } = Layout;

interface MainLayoutProps {
  children: React.ReactNode;
}

export const MainLayout = ({ children }: MainLayoutProps) => {
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
    return [];
  };

  const [openKeys, setOpenKeys] = useState<string[]>(getOpenKeys());

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
  ];

  // 用户下拉菜单
  const userMenuItems: MenuProps['items'] = [
    {
      key: 'profile',
      icon: <UserOutlined />,
      label: t('text.user.profile'),
    },
    {
      key: 'settings',
      icon: <SettingOutlined />,
      label: t('text.system.settings'),
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
          items={menuItems}
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
            <Dropdown
              menu={{
                items: userMenuItems,
                onClick: handleUserMenuClick,
              }}
              placement="bottomRight"
            >
              <Space style={{ cursor: 'pointer' }}>
                <Avatar icon={<UserOutlined />} />
                <span>{t('text.user.admin')}</span>
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
          {children}
        </Content>
      </Layout>
    </Layout>
  );
};
