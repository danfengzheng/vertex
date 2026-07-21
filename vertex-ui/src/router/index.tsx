import { Routes, Route, Navigate } from 'react-router-dom';
import { MainLayout } from '../components/Layout/MainLayout';
import { AuthGuard } from '../components/AuthGuard';
import { PermissionGuard } from '../components/PermissionGuard';
import { Login } from '../pages/login/Login';
import { UserManagement } from '../pages/user/UserManagement';
import { MenuManagement } from '../pages/menu/MenuManagement';
import { RoleManagement } from '../pages/role/RoleManagement';
import { DataSourceManagement } from '../pages/quote/DataSourceManagement';
import { KLineQuery } from '../pages/quote/KLineQuery';
import { VolumeSurgeConfigPage } from '../pages/quote/VolumeSurgeConfig';
import { StrategyConfig } from '../pages/strategy/StrategyConfig';
import { SignalMonitor } from '../pages/strategy/SignalMonitor';
import { StrategyGuide } from '../pages/guide/StrategyGuide';
import { ExchangeAccountManagement } from '../pages/trading/ExchangeAccountManagement';
import { OrderHistory } from '../pages/trading/OrderHistory';
import { PositionMonitor } from '../pages/trading/PositionMonitor';
import { PnlAnalysis } from '../pages/trading/PnlAnalysis';
import { SymbolManagement } from '../pages/trading/SymbolManagement';
import { TokenList } from '../pages/chain/TokenList';
import { AlertConfig } from '../pages/chain/AlertConfig';
import { SourceConfig } from '../pages/chain/SourceConfig';
import { AiDashboard } from '../pages/ai/AiDashboard';
import { AiStatusPage } from '../pages/ai/AiStatus';
import { AiConfig } from '../pages/ai/AiConfig';

export const AppRouter = () => {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route
        path="/"
        element={
          <AuthGuard>
            <MainLayout />
          </AuthGuard>
        }
      >
        <Route index element={<Navigate to="/guide/strategy" replace />} />

        {/* 系统管理 */}
        <Route path="user" element={
          <PermissionGuard path="/user"><UserManagement /></PermissionGuard>
        } />
        <Route path="menu" element={
          <PermissionGuard path="/menu"><MenuManagement /></PermissionGuard>
        } />
        <Route path="role" element={
          <PermissionGuard path="/role"><RoleManagement /></PermissionGuard>
        } />

        {/* 行情 */}
        <Route path="quote/source" element={
          <PermissionGuard path="/quote/source"><DataSourceManagement /></PermissionGuard>
        } />
        <Route path="quote/kline" element={
          <PermissionGuard path="/quote/kline"><KLineQuery /></PermissionGuard>
        } />
        <Route path="quote/volume-surge" element={
          <PermissionGuard path="/quote/volume-surge"><VolumeSurgeConfigPage /></PermissionGuard>
        } />

        {/* 策略 */}
        <Route path="strategy/config" element={
          <PermissionGuard path="/strategy/config"><StrategyConfig /></PermissionGuard>
        } />
        <Route path="strategy/signals" element={
          <PermissionGuard path="/strategy/signals"><SignalMonitor /></PermissionGuard>
        } />

        {/* 交易 */}
        <Route path="trading/accounts" element={
          <PermissionGuard path="/trading/accounts"><ExchangeAccountManagement /></PermissionGuard>
        } />
        <Route path="trading/orders" element={
          <PermissionGuard path="/trading/orders"><OrderHistory /></PermissionGuard>
        } />
        <Route path="trading/positions" element={
          <PermissionGuard path="/trading/positions"><PositionMonitor /></PermissionGuard>
        } />
        <Route path="trading/pnl" element={
          <PermissionGuard path="/trading/pnl"><PnlAnalysis /></PermissionGuard>
        } />
        <Route path="trading/symbols" element={
          <PermissionGuard path="/trading/symbols"><SymbolManagement /></PermissionGuard>
        } />

        {/* 链上分析 */}
        <Route path="chain/tokens" element={
          <PermissionGuard path="/chain/tokens"><TokenList /></PermissionGuard>
        } />
        <Route path="chain/alerts" element={
          <PermissionGuard path="/chain/alerts"><AlertConfig /></PermissionGuard>
        } />
        <Route path="chain/source-config" element={
          <PermissionGuard path="/chain/source-config"><SourceConfig /></PermissionGuard>
        } />

        {/* AI 分析（权限码 ai:dashboard / ai:status，由 sys_menu V19 迁移建立） */}
        <Route path="ai/dashboard" element={
          <PermissionGuard path="/ai/dashboard"><AiDashboard /></PermissionGuard>
        } />
        <Route path="ai/status" element={
          <PermissionGuard path="/ai/status"><AiStatusPage /></PermissionGuard>
        } />
        <Route path="ai/config" element={
          <PermissionGuard path="/ai/config"><AiConfig /></PermissionGuard>
        } />

        {/* 使用指南（无权限码，所有已登录用户可访问） */}
        <Route path="guide/strategy" element={<StrategyGuide />} />
      </Route>
    </Routes>
  );
};
