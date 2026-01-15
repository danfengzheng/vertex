import { Routes, Route, Navigate } from 'react-router-dom';
import { MainLayout } from '../components/Layout/MainLayout';
import { UserManagement } from '../pages/user/UserManagement';
import { MenuManagement } from '../pages/menu/MenuManagement';
import { RoleManagement } from '../pages/role/RoleManagement';

export const AppRouter = () => {
  return (
    <MainLayout>
      <Routes>
        <Route path="/" element={<Navigate to="/user" replace />} />
        <Route path="/user" element={<UserManagement />} />
        <Route path="/menu" element={<MenuManagement />} />
        <Route path="/role" element={<RoleManagement />} />
      </Routes>
    </MainLayout>
  );
};
