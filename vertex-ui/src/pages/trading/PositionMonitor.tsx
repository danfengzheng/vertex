import { useState, useCallback, useEffect } from 'react';
import { Table, Button, Space, message, Tag, Select, Popconfirm } from 'antd';
import { formatServerTime } from '../../utils/date';
import { ReloadOutlined, CloseCircleOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import {
  positionApi,
  PositionVO,
  PositionStatus,
  PositionSide,
  ExecutionMode,
  MarketType,
  PositionQueryDTO,
} from '../../api/trading';
import { strategyApi, StrategyVO } from '../../api/strategy';

export const PositionMonitor = () => {
  const { t } = useTranslation();
  const [loading, setLoading] = useState(false);
  const [positions, setPositions] = useState<PositionVO[]>([]);
  const [total, setTotal] = useState(0);
  const [query, setQuery] = useState<PositionQueryDTO>({ pageNum: 1, pageSize: 20 });
  const [strategies, setStrategies] = useState<StrategyVO[]>([]);

  useEffect(() => {
    strategyApi.page({ pageNum: 1, pageSize: 200 }).then(res => {
      setStrategies(res.data?.records || []);
    }).catch(() => {});
  }, []);

  const fetchPositions = useCallback(async () => {
    setLoading(true);
    try {
      const res = await positionApi.page(query);
      setPositions(res.data?.records || []);
      setTotal(res.data?.total || 0);
    } catch (e) {
      // handled by interceptor
    } finally {
      setLoading(false);
    }
  }, [query]);

  useEffect(() => {
    fetchPositions();
  }, [fetchPositions]);

  // Auto-refresh every 10 seconds for open positions
  useEffect(() => {
    const timer = setInterval(() => {
      if (query.status === undefined || query.status === 'OPEN') {
        fetchPositions();
      }
    }, 10000);
    return () => clearInterval(timer);
  }, [fetchPositions, query.status]);

  const handleClose = async (id: string) => {
    await positionApi.close(id);
    message.success(t('text.trading.closePosition') + ' OK');
    fetchPositions();
  };

  const pnlColor = (val: string | number | null) => {
    if (!val) return undefined;
    const num = typeof val === 'string' ? parseFloat(val) : val;
    if (num > 0) return '#52c41a';
    if (num < 0) return '#ff4d4f';
    return undefined;
  };

  const columns = [
    {
      title: t('text.strategy.name'),
      dataIndex: 'strategyName',
      key: 'strategyName',
      width: 150,
      render: (val: string) => val || '-',
    },
    {
      title: t('text.strategy.symbol'),
      dataIndex: 'symbol',
      key: 'symbol',
      width: 120,
    },
    {
      title: t('text.trading.side'),
      dataIndex: 'side',
      key: 'side',
      width: 80,
      render: (val: PositionSide) => (
        <Tag color={val === 'LONG' ? 'success' : 'error'}>{val}</Tag>
      ),
    },
    {
      title: t('text.trading.quantity'),
      dataIndex: 'quantity',
      key: 'quantity',
      width: 120,
    },
    {
      title: t('text.trading.entryPrice'),
      dataIndex: 'entryPrice',
      key: 'entryPrice',
      width: 120,
    },
    {
      title: t('text.trading.currentPrice'),
      dataIndex: 'currentPrice',
      key: 'currentPrice',
      width: 120,
      render: (val: string) => val || '-',
    },
    {
      title: t('text.trading.unrealizedPnl'),
      dataIndex: 'unrealizedPnl',
      key: 'unrealizedPnl',
      width: 130,
      render: (val: string) => (
        <span style={{ color: pnlColor(val), fontWeight: 'bold' }}>
          {val ? (parseFloat(val) > 0 ? '+' : '') + parseFloat(val).toFixed(4) : '-'}
        </span>
      ),
    },
    {
      title: t('text.trading.realizedPnl'),
      dataIndex: 'realizedPnl',
      key: 'realizedPnl',
      width: 130,
      render: (val: string) => (
        <span style={{ color: pnlColor(val) }}>
          {val ? (parseFloat(val) > 0 ? '+' : '') + parseFloat(val).toFixed(4) : '-'}
        </span>
      ),
    },
    {
      title: t('text.trading.stopLoss') + ' / ' + t('text.trading.takeProfit'),
      key: 'sltp',
      width: 180,
      render: (_: any, record: PositionVO) => (
        <Space>
          <span style={{ color: '#ff4d4f' }}>{record.stopLoss || '-'}</span>
          <span>/</span>
          <span style={{ color: '#52c41a' }}>{record.takeProfit || '-'}</span>
        </Space>
      ),
    },
    {
      title: '杠杆',
      dataIndex: 'leverage',
      key: 'leverage',
      width: 70,
      render: (val: number, record: PositionVO) =>
        record.marketType && record.marketType !== 'SPOT' && val ? `${val}x` : '-',
    },
    {
      title: '保证金',
      dataIndex: 'marginType',
      key: 'marginType',
      width: 80,
      render: (val: string, record: PositionVO) =>
        record.marketType && record.marketType !== 'SPOT' && val ? (
          <Tag color={val === 'ISOLATED' ? 'orange' : 'cyan'}>{val === 'ISOLATED' ? '逐仓' : '全仓'}</Tag>
        ) : null,
    },
    {
      title: '强平价',
      dataIndex: 'liquidationPrice',
      key: 'liquidationPrice',
      width: 110,
      render: (val: string, record: PositionVO) =>
        record.marketType && record.marketType !== 'SPOT' ? (
          <span style={{ color: '#ff4d4f' }}>{val || '-'}</span>
        ) : null,
    },
    {
      title: '资金费率',
      dataIndex: 'fundingRate',
      key: 'fundingRate',
      width: 100,
      render: (val: string, record: PositionVO) => {
        if (!record.marketType || record.marketType === 'SPOT' || !val) return null;
        const rate = parseFloat(val);
        const pct = (rate * 100).toFixed(4);
        return <span style={{ color: rate >= 0 ? '#52c41a' : '#ff4d4f' }}>{pct}%</span>;
      },
    },
    {
      title: t('text.trading.status'),
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (val: PositionStatus) => (
        <Tag color={val === 'OPEN' ? 'processing' : 'default'}>
          {val === 'OPEN' ? t('text.trading.open') : t('text.trading.closed')}
        </Tag>
      ),
    },
    {
      title: t('text.trading.executionMode'),
      dataIndex: 'tradeMode',
      key: 'tradeMode',
      width: 80,
      render: (val: ExecutionMode) => (
        <Tag color={val === 'LIVE' ? 'red' : 'blue'}>
          {val === 'LIVE' ? t('text.trading.live') : t('text.trading.paper')}
        </Tag>
      ),
    },
    {
      title: t('common.createTime'),
      dataIndex: 'createTime',
      key: 'createTime',
      width: 170,
      render: (val: string) => formatServerTime(val),
    },
    {
      title: t('common.updateTime'),
      dataIndex: 'updateTime',
      key: 'updateTime',
      width: 170,
      render: (val: string) => formatServerTime(val),
    },
    {
      title: t('common.operation'),
      key: 'action',
      width: 120,
      render: (_: any, record: PositionVO) => (
        record.status === 'OPEN' ? (
          <Popconfirm title={t('text.trading.closePosition') + '?'} onConfirm={() => handleClose(record.id)}>
            <Button type="link" danger icon={<CloseCircleOutlined />}>
              {t('text.trading.closePosition')}
            </Button>
          </Popconfirm>
        ) : null
      ),
    },
  ];

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', gap: 8 }}>
        <Select
          allowClear
          placeholder={t('text.trading.status')}
          style={{ width: 150 }}
          onChange={(val) => setQuery({ ...query, status: val, pageNum: 1 })}
          options={[
            { value: 'OPEN', label: t('text.trading.open') },
            { value: 'CLOSED', label: t('text.trading.closed') },
          ]}
        />
        <Select
          allowClear
          placeholder={t('text.trading.executionMode')}
          style={{ width: 120 }}
          onChange={(val) => setQuery({ ...query, tradeMode: val, pageNum: 1 })}
          options={[
            { value: 'LIVE', label: t('text.trading.live') },
            { value: 'PAPER', label: t('text.trading.paper') },
          ]}
        />
        <Select
          allowClear
          placeholder="市场类型"
          style={{ width: 140 }}
          onChange={(val: MarketType) => setQuery({ ...query, marketType: val, pageNum: 1 })}
          options={[
            { value: 'SPOT', label: '现货' },
            { value: 'USDM', label: 'U本位合约' },
            { value: 'COINM', label: '币本位合约' },
          ]}
        />
        <Select
          allowClear
          placeholder={t('text.strategy.name')}
          style={{ width: 180 }}
          showSearch
          optionFilterProp="label"
          onChange={(val) => setQuery({ ...query, strategyId: val, pageNum: 1 })}
          options={strategies.map(s => ({ value: s.id, label: s.name }))}
        />
        <Button icon={<ReloadOutlined />} onClick={fetchPositions}>{t('text.trading.refresh')}</Button>
      </div>
      <Table
        columns={columns}
        dataSource={positions}
        loading={loading}
        rowKey="id"
        scroll={{ x: 1950 }}
        pagination={{
          current: query.pageNum,
          pageSize: query.pageSize,
          total,
          onChange: (page, size) => setQuery({ ...query, pageNum: page, pageSize: size }),
        }}
      />
    </div>
  );
};
