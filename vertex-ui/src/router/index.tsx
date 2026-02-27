import { Routes, Route, Navigate } from 'react-router-dom';
import { MainLayout } from '../components/Layout/MainLayout';
import { AuthGuard } from '../components/AuthGuard';
import { Login } from '../pages/login/Login';
import { UserManagement } from '../pages/user/UserManagement';
import { MenuManagement } from '../pages/menu/MenuManagement';
import { RoleManagement } from '../pages/role/RoleManagement';
import { DataSourceManagement } from '../pages/quote/DataSourceManagement';
import { KLineQuery } from '../pages/quote/KLineQuery';
import { StrategyConfig } from '../pages/strategy/StrategyConfig';
import { SignalMonitor } from '../pages/strategy/SignalMonitor';
import { StrategyGuide } from '../pages/guide/StrategyGuide';
import { ExchangeAccountManagement } from '../pages/trading/ExchangeAccountManagement';
import { OrderHistory } from '../pages/trading/OrderHistory';
import { PositionMonitor } from '../pages/trading/PositionMonitor';
import { PnlAnalysis } from '../pages/trading/PnlAnalysis';
import { TokenList } from '../pages/chain/TokenList';
import { AlertConfig } from '../pages/chain/AlertConfig';

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
        <Route path="user" element={<UserManagement />} />
        <Route path="menu" element={<MenuManagement />} />
        <Route path="role" element={<RoleManagement />} />
        <Route path="quote/source" element={<DataSourceManagement />} />
        <Route path="quote/kline" element={<KLineQuery />} />
        <Route path="strategy/config" element={<StrategyConfig />} />
        <Route path="strategy/signals" element={<SignalMonitor />} />
        <Route path="trading/accounts" element={<ExchangeAccountManagement />} />
        <Route path="trading/orders" element={<OrderHistory />} />
        <Route path="trading/positions" element={<PositionMonitor />} />
        <Route path="trading/pnl" element={<PnlAnalysis />} />
        <Route path="guide/strategy" element={<StrategyGuide />} />
        <Route path="chain/tokens" element={<TokenList />} />
        <Route path="chain/alerts" element={<AlertConfig />} />
      </Route>
    </Routes>
  );
};
