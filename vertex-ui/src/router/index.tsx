import { Routes, Route, Navigate } from 'react-router-dom';
import { MainLayout } from '../components/Layout/MainLayout';
import { UserManagement } from '../pages/user/UserManagement';
import { MenuManagement } from '../pages/menu/MenuManagement';
import { RoleManagement } from '../pages/role/RoleManagement';
import { DataSourceManagement } from '../pages/quote/DataSourceManagement';
import { KLineQuery } from '../pages/quote/KLineQuery';
import { StrategyConfig } from '../pages/strategy/StrategyConfig';
import { SignalMonitor } from '../pages/strategy/SignalMonitor';
import { StrategyGuide } from '../pages/guide/StrategyGuide';

export const AppRouter = () => {
  return (
    <MainLayout>
      <Routes>
        <Route path="/" element={<Navigate to="/guide/strategy" replace />} />
        <Route path="/user" element={<UserManagement />} />
        <Route path="/menu" element={<MenuManagement />} />
        <Route path="/role" element={<RoleManagement />} />
        <Route path="/quote/source" element={<DataSourceManagement />} />
        <Route path="/quote/kline" element={<KLineQuery />} />
        <Route path="/strategy/config" element={<StrategyConfig />} />
        <Route path="/strategy/signals" element={<SignalMonitor />} />
        <Route path="/guide/strategy" element={<StrategyGuide />} />
      </Routes>
    </MainLayout>
  );
};
