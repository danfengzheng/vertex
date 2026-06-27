import { useState, useCallback, useEffect } from 'react';
import { Card, Table, Button, Space, Select, DatePicker, Input, Row, Col, Spin } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import {
  pnlAnalysisApi,
  exchangeAccountApi,
  PnlAnalysisVO,
  PnlAnalysisQueryDTO,
  StrategyPnlItem,
  ExchangeAccountVO,
  AssetBalanceVO,
  DailyPnlVO,
} from '../../api/trading';
import dayjs from 'dayjs';
import {
  ComposedChart,
  Bar,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  Legend,
  ResponsiveContainer,
  Cell,
} from 'recharts';

const pnlColor = (val: string | number | null | undefined) => {
  if (val == null) return undefined;
  const num = typeof val === 'string' ? parseFloat(val) : val;
  if (num > 0) return '#52c41a';
  if (num < 0) return '#ff4d4f';
  return undefined;
};

const formatPnl = (val: string | number | null | undefined): string => {
  if (val == null) return '0';
  const num = typeof val === 'string' ? parseFloat(val) : val;
  if (Number.isNaN(num)) return '0';
  return num.toFixed(4);
};

const toNum = (val: string | number | null | undefined): number => {
  if (val == null) return 0;
  const num = typeof val === 'string' ? parseFloat(val) : val;
  return Number.isNaN(num) ? 0 : num;
};

export const PnlAnalysis = () => {
  const { t } = useTranslation();

  // ─── 过滤条件 ─────────────────────────────────────
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState<PnlAnalysisVO | null>(null);
  const [query, setQuery] = useState<PnlAnalysisQueryDTO>({});
  const [dateRange, setDateRange] = useState<[dayjs.Dayjs | null, dayjs.Dayjs | null]>([null, null]);

  // ─── 账户余额 ─────────────────────────────────────
  const [accounts, setAccounts] = useState<ExchangeAccountVO[]>([]);
  const [selectedAccountId, setSelectedAccountId] = useState<string | undefined>(undefined);
  const [balances, setBalances] = useState<AssetBalanceVO[]>([]);
  const [balanceLoading, setBalanceLoading] = useState(false);

  // ─── 每日盈亏 ─────────────────────────────────────
  const [dailyPnl, setDailyPnl] = useState<DailyPnlVO[]>([]);
  const [dailyLoading, setDailyLoading] = useState(false);

  // 加载账户列表（用于余额下拉）
  useEffect(() => {
    exchangeAccountApi.list().then((res) => {
      setAccounts(res.data ?? []);
    });
  }, []);

  const buildParams = useCallback((): PnlAnalysisQueryDTO => {
    const params: PnlAnalysisQueryDTO = { ...query };
    if (dateRange[0]) params.closedAtStart = dateRange[0].toISOString();
    if (dateRange[1]) params.closedAtEnd = dateRange[1].toISOString();
    return params;
  }, [query, dateRange]);

  const fetchAnalysis = useCallback(async () => {
    setLoading(true);
    try {
      const res = await pnlAnalysisApi.getAnalysis(buildParams());
      setData(res.data ?? null);
    } catch {
      setData(null);
    } finally {
      setLoading(false);
    }
  }, [buildParams]);

  const fetchDailyPnl = useCallback(async () => {
    setDailyLoading(true);
    try {
      const res = await pnlAnalysisApi.getDailyPnl(buildParams());
      setDailyPnl(res.data ?? []);
    } catch {
      setDailyPnl([]);
    } finally {
      setDailyLoading(false);
    }
  }, [buildParams]);

  const fetchBalance = useCallback(async (accountId: string) => {
    setBalanceLoading(true);
    try {
      const res = await exchangeAccountApi.getBalance(accountId);
      setBalances(res.data ?? []);
    } catch {
      setBalances([]);
    } finally {
      setBalanceLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchAnalysis();
    fetchDailyPnl();
  }, [fetchAnalysis, fetchDailyPnl]);

  const handleRefresh = () => {
    fetchAnalysis();
    fetchDailyPnl();
  };

  const handleAccountChange = (id: string) => {
    setSelectedAccountId(id);
    if (id) fetchBalance(id);
    else setBalances([]);
  };

  // ─── 表格列 ───────────────────────────────────────
  const strategyColumns = [
    {
      title: t('text.trading.strategyId'),
      dataIndex: 'strategyId',
      key: 'strategyId',
      render: (_: unknown, row: StrategyPnlItem) => row.strategyName || String(row.strategyId ?? '-'),
    },
    {
      title: t('text.trading.closedCount'),
      dataIndex: 'closedCount',
      key: 'closedCount',
      width: 100,
    },
    {
      title: t('text.trading.realizedPnl'),
      dataIndex: 'realizedPnl',
      key: 'realizedPnl',
      render: (val: string) => (
        <span style={{ color: pnlColor(val) }}>{formatPnl(val)}</span>
      ),
    },
  ];

  const symbolColumns = [
    {
      title: t('text.strategy.symbol'),
      dataIndex: 'symbol',
      key: 'symbol',
    },
    {
      title: t('text.trading.closedCount'),
      dataIndex: 'closedCount',
      key: 'closedCount',
      width: 100,
    },
    {
      title: t('text.trading.realizedPnl'),
      dataIndex: 'realizedPnl',
      key: 'realizedPnl',
      render: (val: string) => (
        <span style={{ color: pnlColor(val) }}>{formatPnl(val)}</span>
      ),
    },
  ];

  const balanceColumns = [
    { title: t('text.trading.asset'), dataIndex: 'asset', key: 'asset', width: 100 },
    {
      title: t('text.trading.free'),
      dataIndex: 'free',
      key: 'free',
      render: (val: string | number) => formatPnl(val),
    },
    {
      title: t('text.trading.locked'),
      dataIndex: 'locked',
      key: 'locked',
      render: (val: string | number) => formatPnl(val),
    },
    {
      title: t('text.trading.total'),
      dataIndex: 'total',
      key: 'total',
      render: (val: string | number) => (
        <span style={{ fontWeight: 600 }}>{formatPnl(val)}</span>
      ),
    },
  ];

  // recharts 数据格式化
  const chartData = dailyPnl.map((d) => ({
    date: d.date,
    dailyPnl: toNum(d.dailyPnl),
    cumulativePnl: toNum(d.cumulativePnl),
  }));

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      {/* ─── 过滤条件 ─────────────────────────────── */}
      <Card size="small" title={t('text.trading.filter')}>
        <Space wrap>
          <Select
            placeholder={t('text.trading.executionMode')}
            style={{ width: 140 }}
            allowClear
            value={query.tradeMode}
            onChange={(v) => setQuery((q) => ({ ...q, tradeMode: v }))}
            options={[
              { value: 'LIVE', label: t('text.trading.live') },
              { value: 'PAPER', label: t('text.trading.paper') },
            ]}
          />
          <Input
            placeholder={t('text.strategy.symbol')}
            style={{ width: 140 }}
            allowClear
            value={query.symbol ?? ''}
            onChange={(e) => setQuery((q) => ({ ...q, symbol: e.target.value || undefined }))}
          />
          <DatePicker.RangePicker
            showTime={{
              defaultValue: [
                dayjs('00:00:00', 'HH:mm:ss'),
                dayjs('23:59:59', 'HH:mm:ss'),
              ],
            }}
            value={dateRange}
            onChange={(dates) => setDateRange(dates as [dayjs.Dayjs | null, dayjs.Dayjs | null])}
          />
          <Button type="primary" icon={<ReloadOutlined />} onClick={handleRefresh} loading={loading || dailyLoading}>
            {t('text.trading.refresh')}
          </Button>
        </Space>
      </Card>

      {/* ─── 实盘账户余额 ─────────────────────────── */}
      <Card
        size="small"
        title={t('text.trading.accountBalance')}
        extra={
          <Button
            size="small"
            icon={<ReloadOutlined />}
            loading={balanceLoading}
            disabled={!selectedAccountId}
            onClick={() => selectedAccountId && fetchBalance(selectedAccountId)}
          >
            {t('text.trading.refresh')}
          </Button>
        }
      >
        <Space direction="vertical" style={{ width: '100%' }}>
          <Select
            placeholder={t('text.trading.selectAccount')}
            style={{ width: 260 }}
            allowClear
            value={selectedAccountId}
            onChange={handleAccountChange}
            options={accounts.map((a) => ({ value: a.id, label: `${a.name} (${a.exchange})` }))}
          />
          {selectedAccountId && (
            <Spin spinning={balanceLoading}>
              <Table
                size="small"
                rowKey="asset"
                columns={balanceColumns}
                dataSource={balances}
                pagination={false}
              />
            </Spin>
          )}
        </Space>
      </Card>

      {/* ─── 每日盈亏走势图 ───────────────────────── */}
      <Card size="small" title={t('text.trading.dailyPnlTrend')} loading={dailyLoading}>
        {chartData.length === 0 ? (
          <div style={{ textAlign: 'center', padding: '24px 0', color: '#999' }}>
            {t('text.trading.noData')}
          </div>
        ) : (
          <ResponsiveContainer width="100%" height={240}>
            <ComposedChart data={chartData} margin={{ top: 8, right: 24, left: 0, bottom: 0 }}>
              <CartesianGrid strokeDasharray="3 3" />
              <XAxis dataKey="date" tick={{ fontSize: 12 }} />
              <YAxis yAxisId="left" tick={{ fontSize: 12 }} />
              <YAxis yAxisId="right" orientation="right" tick={{ fontSize: 12 }} />
              <Tooltip
                formatter={(value: number, name: string) => [
                  value.toFixed(4),
                  name === 'dailyPnl' ? t('text.trading.dailyPnl') : t('text.trading.cumulativePnl'),
                ]}
              />
              <Legend
                formatter={(value: string) =>
                  value === 'dailyPnl' ? t('text.trading.dailyPnl') : t('text.trading.cumulativePnl')
                }
              />
              <Bar yAxisId="left" dataKey="dailyPnl" name="dailyPnl">
                {chartData.map((entry, index) => (
                  <Cell key={`cell-${index}`} fill={entry.dailyPnl >= 0 ? '#52c41a' : '#ff4d4f'} />
                ))}
              </Bar>
              <Line
                yAxisId="right"
                type="monotone"
                dataKey="cumulativePnl"
                name="cumulativePnl"
                stroke="#1677ff"
                strokeWidth={2}
                dot={false}
              />
            </ComposedChart>
          </ResponsiveContainer>
        )}
      </Card>

      {/* ─── 三张汇总卡片 ─────────────────────────── */}
      <Row gutter={16}>
        <Col span={8}>
          <Card size="small" title={t('text.trading.totalPnl')} loading={loading}>
            <span style={{ fontSize: 18, fontWeight: 600, color: pnlColor(data?.totalPnl) }}>
              {formatPnl(data?.totalPnl)}
            </span>
          </Card>
        </Col>
        <Col span={8}>
          <Card size="small" title={t('text.trading.paperPnl')} loading={loading}>
            <span style={{ fontSize: 18, fontWeight: 600, color: pnlColor(data?.paperPnl) }}>
              {formatPnl(data?.paperPnl)}
            </span>
          </Card>
        </Col>
        <Col span={8}>
          <Card size="small" title={t('text.trading.livePnl')} loading={loading}>
            <span style={{ fontSize: 18, fontWeight: 600, color: pnlColor(data?.livePnl) }}>
              {formatPnl(data?.livePnl)}
            </span>
          </Card>
        </Col>
      </Row>

      {/* ─── 策略盈亏表 ───────────────────────────── */}
      <Card size="small" title={t('text.trading.byStrategy')} loading={loading}>
        <Table
          size="small"
          rowKey={(r) => String(r.strategyId)}
          columns={strategyColumns}
          dataSource={data?.byStrategy ?? []}
          pagination={false}
        />
      </Card>

      {/* ─── 交易对盈亏表 ─────────────────────────── */}
      <Card size="small" title={t('text.trading.bySymbol')} loading={loading}>
        <Table
          size="small"
          rowKey="symbol"
          columns={symbolColumns}
          dataSource={data?.bySymbol ?? []}
          pagination={false}
        />
      </Card>
    </Space>
  );
};
