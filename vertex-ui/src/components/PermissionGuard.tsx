import { ReactNode } from 'react';
import { Result, Spin } from 'antd';
import { usePermission } from '../contexts/PermissionContext';

interface Props {
  /** 菜单路径，与 sys_menu.path 一致，如 "/user"、"/trade/order" */
  path: string;
  children: ReactNode;
}

/**
 * 前端路由权限守卫
 * <p>
 * - allowedPaths === null：权限加载中，显示全屏 Loading
 * - allowedPaths 中不包含 path：无权访问，显示 403 页面
 * - 否则渲染子组件
 */
export const PermissionGuard = ({ path, children }: Props) => {
  const { allowedPaths } = usePermission();

  if (allowedPaths === null) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100%', minHeight: 300 }}>
        <Spin size="large" />
      </div>
    );
  }

  if (!allowedPaths.has(path)) {
    return (
      <Result
        status="403"
        title="403"
        subTitle="抱歉，您没有权限访问该页面。"
      />
    );
  }

  return <>{children}</>;
};
