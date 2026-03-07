import { useState, useEffect } from 'react';
import { Form, Input, Button, Card, message } from 'antd';
import { UserOutlined, LockOutlined } from '@ant-design/icons';
import { useNavigate, useLocation } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { authApi } from '../../api/auth';
import { usePermission } from '../../contexts/PermissionContext';

export const Login = () => {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const location = useLocation();
  const { refreshPermissions } = usePermission();
  const [loading, setLoading] = useState(false);
  const [form] = Form.useForm();

  const from = (location.state as { from?: { pathname: string } })?.from?.pathname || '/';

  // 已登录则直接跳转
  useEffect(() => {
    if (localStorage.getItem('token')) {
      navigate(from, { replace: true });
    }
  }, [navigate, from]);

  const onFinish = async (values: { username: string; password: string }) => {
    setLoading(true);
    try {
      const res = await authApi.login(values);
      const data = res.data;
      if (data?.token) {
        localStorage.setItem('token', data.token);
        if (data.user) {
          localStorage.setItem('user', JSON.stringify(data.user));
        }
        message.success(t('text.auth.loginSuccess'));
        // 登录后立即刷新权限，确保 PermissionContext 拿到菜单再跳转，
        // 避免首次登录侧边栏空白（Context 的 useEffect 只在挂载时运行一次）
        await refreshPermissions();
        navigate(from, { replace: true });
      }
    } catch (e) {
      // 错误已在 request 拦截器或接口返回中处理
    } finally {
      setLoading(false);
    }
  };

  return (
    <div
      style={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: 'linear-gradient(135deg, #1a1a2e 0%, #16213e 50%, #0f3460 100%)',
      }}
    >
      <Card
        title={t('text.auth.loginTitle')}
        style={{ width: 400 }}
        styles={{ header: { textAlign: 'center', fontSize: 22 } }}
      >
        <Form
          form={form}
          layout="vertical"
          onFinish={onFinish}
          autoComplete="off"
        >
          <Form.Item
            name="username"
            rules={[{ required: true, message: t('text.auth.usernameRequired') }]}
          >
            <Input
              prefix={<UserOutlined />}
              placeholder={t('text.user.username')}
              size="large"
            />
          </Form.Item>
          <Form.Item
            name="password"
            rules={[{ required: true, message: t('text.auth.passwordRequired') }]}
          >
            <Input.Password
              prefix={<LockOutlined />}
              placeholder={t('text.user.password')}
              size="large"
            />
          </Form.Item>
          <Form.Item>
            <Button
              type="primary"
              htmlType="submit"
              loading={loading}
              block
              size="large"
            >
              {t('text.auth.login')}
            </Button>
          </Form.Item>
        </Form>
      </Card>
    </div>
  );
};
