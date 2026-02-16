import { useState, useCallback, useEffect } from 'react';
import { Table, Button, Space, message, Tag, Select, Popconfirm } from 'antd';
import { ReloadOutlined, CloseCircleOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import {
  positionApi,
  PositionVO,
  PositionStatus,
  PositionSide,
  ExecutionMode,
  PositionQueryDTO,
} from '../../api/trading';

export const PositionMonitor = () => {
  const { t } = useTranslation();
  const [loading, setLoading] = useState(false);
  const [positions, setPositions] = useState<PositionVO[]>([]);
  const [total, setTotal] = useState(0);
  const [query, setQuery] = useState<PositionQueryDTO>({ pageNum: 1, pageSize: 20 });

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
        <Button icon={<ReloadOutlined />} onClick={fetchPositions}>{t('text.trading.refresh')}</Button>
      </div>
      <Table
        columns={columns}
        dataSource={positions}
        loading={loading}
        rowKey="id"
        scroll={{ x: 1400 }}
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
