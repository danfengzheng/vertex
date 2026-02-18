import { useState, useCallback, useEffect } from 'react';
import dayjs from 'dayjs';
import { Table, Button, Space, message, Tag, Select, Popconfirm } from 'antd';
import {
  ReloadOutlined,
  CheckOutlined,
  CloseOutlined,
  StopOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import {
  orderApi,
  OrderVO,
  OrderStatus,
  OrderSide,
  ExecutionMode,
  OrderQueryDTO,
} from '../../api/trading';

const ORDER_STATUS_COLOR: Record<OrderStatus, string> = {
  PENDING: 'warning',
  SUBMITTED: 'processing',
  FILLED: 'success',
  PARTIALLY_FILLED: 'cyan',
  CANCELLED: 'default',
  REJECTED: 'error',
  EXPIRED: 'default',
  SIMULATED: 'blue',
};

/** 订单状态 → i18n key 映射 */
const ORDER_STATUS_I18N_KEY: Record<OrderStatus, string> = {
  PENDING: 'pending',
  SUBMITTED: 'submitted',
  FILLED: 'filled',
  PARTIALLY_FILLED: 'partiallyFilled',
  CANCELLED: 'cancelled',
  REJECTED: 'rejected',
  EXPIRED: 'expired',
  SIMULATED: 'simulated',
};

export const OrderHistory = () => {
  const { t } = useTranslation();
  const [loading, setLoading] = useState(false);
  const [orders, setOrders] = useState<OrderVO[]>([]);
  const [total, setTotal] = useState(0);
  const [query, setQuery] = useState<OrderQueryDTO>({ pageNum: 1, pageSize: 20 });

  const fetchOrders = useCallback(async () => {
    setLoading(true);
    try {
      const res = await orderApi.page(query);
      setOrders(res.data?.records || []);
      setTotal(res.data?.total || 0);
    } catch (e) {
      // handled by interceptor
    } finally {
      setLoading(false);
    }
  }, [query]);

  useEffect(() => {
    fetchOrders();
  }, [fetchOrders]);

  const handleConfirm = async (id: string) => {
    await orderApi.confirm(id);
    message.success(t('text.trading.confirm') + ' OK');
    fetchOrders();
  };

  const handleReject = async (id: string) => {
    await orderApi.reject(id);
    message.success(t('text.trading.reject') + ' OK');
    fetchOrders();
  };

  const handleCancel = async (id: string) => {
    await orderApi.cancel(id);
    message.success(t('text.trading.cancel') + ' OK');
    fetchOrders();
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
      render: (val: OrderSide) => (
        <Tag color={val === 'BUY' ? 'success' : 'error'}>
          {val === 'BUY' ? t('text.trading.buy') : t('text.trading.sell')}
        </Tag>
      ),
    },
    {
      title: t('text.trading.orderType'),
      dataIndex: 'orderType',
      key: 'orderType',
      width: 80,
      render: (val: string) => val === 'MARKET' ? t('text.trading.market') : t('text.trading.limit'),
    },
    {
      title: t('text.trading.quantity'),
      dataIndex: 'quantity',
      key: 'quantity',
      width: 120,
    },
    {
      title: t('text.trading.filledPrice'),
      dataIndex: 'filledPrice',
      key: 'filledPrice',
      width: 120,
      render: (val: string) => val || '-',
    },
    {
      title: t('text.trading.filledQty'),
      dataIndex: 'filledQuantity',
      key: 'filledQuantity',
      width: 120,
      render: (val: string) => val || '-',
    },
    {
      title: t('text.trading.status'),
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (val: OrderStatus) => (
        <Tag color={ORDER_STATUS_COLOR[val] || 'default'}>{t(`text.trading.${ORDER_STATUS_I18N_KEY[val]}`)}</Tag>
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
      render: (val: string) => val ? dayjs(val).format('YYYY-MM-DD HH:mm:ss') : '-',
    },
    {
      title: t('common.updateTime'),
      dataIndex: 'updateTime',
      key: 'updateTime',
      width: 170,
      render: (val: string) => val ? dayjs(val).format('YYYY-MM-DD HH:mm:ss') : '-',
    },
    {
      title: t('common.operation'),
      key: 'action',
      width: 200,
      render: (_: any, record: OrderVO) => (
        <Space>
          {record.status === 'PENDING' && (
            <>
              <Popconfirm title={t('text.trading.confirm') + '?'} onConfirm={() => handleConfirm(record.id)}>
                <Button type="link" icon={<CheckOutlined />} style={{ color: '#52c41a' }}>
                  {t('text.trading.confirm')}
                </Button>
              </Popconfirm>
              <Button type="link" danger icon={<CloseOutlined />} onClick={() => handleReject(record.id)}>
                {t('text.trading.reject')}
              </Button>
            </>
          )}
          {(record.status === 'PENDING' || record.status === 'SUBMITTED') && (
            <Popconfirm title={t('text.trading.cancel') + '?'} onConfirm={() => handleCancel(record.id)}>
              <Button type="link" icon={<StopOutlined />}>{t('text.trading.cancel')}</Button>
            </Popconfirm>
          )}
        </Space>
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
            { value: 'PENDING', label: t('text.trading.pending') },
            { value: 'SUBMITTED', label: t('text.trading.submitted') },
            { value: 'FILLED', label: t('text.trading.filled') },
            { value: 'CANCELLED', label: t('text.trading.cancelled') },
            { value: 'SIMULATED', label: t('text.trading.simulated') },
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
        <Button icon={<ReloadOutlined />} onClick={fetchOrders}>{t('text.trading.refresh')}</Button>
      </div>
      <Table
        columns={columns}
        dataSource={orders}
        loading={loading}
        rowKey="id"
        scroll={{ x: 1200 }}
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
