import { useState, useCallback, useEffect } from 'react';
import { Card, Table, Button, Space, Select, DatePicker, Input, Row, Col } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import {
  pnlAnalysisApi,
  PnlAnalysisVO,
  PnlAnalysisQueryDTO,
  StrategyPnlItem,
} from '../../api/trading';
import dayjs from 'dayjs';

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

export const PnlAnalysis = () => {
  const { t } = useTranslation();
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState<PnlAnalysisVO | null>(null);
  const [query, setQuery] = useState<PnlAnalysisQueryDTO>({});
  const [dateRange, setDateRange] = useState<[dayjs.Dayjs | null, dayjs.Dayjs | null]>([null, null]);

  const fetchAnalysis = useCallback(async () => {
    setLoading(true);
    try {
      const params: PnlAnalysisQueryDTO = { ...query };
      if (dateRange[0]) params.closedAtStart = dateRange[0].toISOString();
      if (dateRange[1]) params.closedAtEnd = dateRange[1].toISOString();
      const res = await pnlAnalysisApi.getAnalysis(params);
      setData(res.data ?? null);
    } catch (e) {
      setData(null);
    } finally {
      setLoading(false);
    }
  }, [query, dateRange]);

  useEffect(() => {
    fetchAnalysis();
  }, [fetchAnalysis]);

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

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
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
            showTime
            value={dateRange}
            onChange={(dates) => setDateRange(dates as [dayjs.Dayjs | null, dayjs.Dayjs | null])}
          />
          <Button type="primary" icon={<ReloadOutlined />} onClick={fetchAnalysis} loading={loading}>
            {t('text.trading.refresh')}
          </Button>
        </Space>
      </Card>

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

      <Card size="small" title={t('text.trading.byStrategy')} loading={loading}>
        <Table
          size="small"
          rowKey={(r) => String(r.strategyId)}
          columns={strategyColumns}
          dataSource={data?.byStrategy ?? []}
          pagination={false}
        />
      </Card>

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
