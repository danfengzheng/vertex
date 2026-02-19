import { Navigate, useLocation } from 'react-router-dom';

const TOKEN_KEY = 'token';

interface AuthGuardProps {
  children: React.ReactNode;
}

/**
 * 认证守卫：无 token 时跳转登录页，登录页带上来源路径以便登录后返回
 */
export const AuthGuard = ({ children }: AuthGuardProps) => {
  const location = useLocation();
  const token = localStorage.getItem(TOKEN_KEY);

  if (!token) {
    return <Navigate to="/login" state={{ from: location }} replace />;
  }

  return <>{children}</>;
};
