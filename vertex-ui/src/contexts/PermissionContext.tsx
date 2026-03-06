import { createContext, useContext, useEffect, useState, ReactNode } from 'react';
import { authApi } from '../api/auth';
import type { MenuVO } from '../api/menu';

interface PermissionContextValue {
  /** null = 正在加载；Set = 已加载完毕（包含当前用户所有可访问的菜单路径） */
  allowedPaths: Set<string> | null;
}

const PermissionContext = createContext<PermissionContextValue>({ allowedPaths: null });

/** 递归收集所有菜单节点的 path */
function collectPaths(nodes: MenuVO[]): string[] {
  const result: string[] = [];
  for (const node of nodes) {
    if (node.path) result.push(node.path);
    if (node.children) result.push(...collectPaths(node.children));
  }
  return result;
}

export const PermissionProvider = ({ children }: { children: ReactNode }) => {
  const [allowedPaths, setAllowedPaths] = useState<Set<string> | null>(null);

  useEffect(() => {
    // 未登录时不发请求，避免 401 → window.location.href = '/login' 死循环
    if (!localStorage.getItem('token')) {
      setAllowedPaths(new Set());
      return;
    }
    authApi.getUserMenus()
      .then(res => {
        if (res.code === 200 && res.data) {
          setAllowedPaths(new Set(collectPaths(res.data)));
        } else {
          // 获取失败：给空集合（后端会兜底鉴权）
          setAllowedPaths(new Set());
        }
      })
      .catch(() => {
        setAllowedPaths(new Set());
      });
  }, []);

  return (
    <PermissionContext.Provider value={{ allowedPaths }}>
      {children}
    </PermissionContext.Provider>
  );
};

export const usePermission = () => useContext(PermissionContext);
