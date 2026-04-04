import { useState, useEffect, useCallback } from 'react';
import { Table, Button, Space, Tag, Select, message, Typography } from 'antd';
import { SyncOutlined, ReloadOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { symbolApi, ExchangeSymbolVO } from '../../api/symbol';
const { Text } = Typography;

const MARKET_TYPE_OPTIONS = [
  { label: 'SPOT', value: 'SPOT' },
  { label: 'USDM', value: 'USDM' },
  { label: 'COINM', value: 'COINM' },
];

export const SymbolManagement = () => {
  const { t } = useTranslation();
  const [loading, setLoading] = useState(false);
  const [syncing, setSyncing] = useState(false);
  const [data, setData] = useState<ExchangeSymbolVO[]>([]);
  const [marketType, setMarketType] = useState<string | undefined>(undefined);

  const fetchSymbols = useCallback(async (mt?: string) => {
    setLoading(true);
    try {
      const res = await symbolApi.list('binance', mt);
      setData(res.data || []);
    } catch {
      // error handled by interceptor
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchSymbols(marketType);
  }, [fetchSymbols, marketType]);

  const handleSync = async () => {
    setSyncing(true);
    try {
      const res = await symbolApi.sync();
      message.success(t('message.trading.symbolSyncSuccess', { count: res.data ?? 0 }));
      fetchSymbols(marketType);
    } catch {
      // error handled by interceptor
    } finally {
      setSyncing(false);
    }
  };

  const columns = [
    {
      title: t('text.trading.symbolMgmt.symbol'),
      dataIndex: 'symbol',
      key: 'symbol',
      width: 140,
      render: (v: string) => <Text strong>{v}</Text>,
    },
    {
      title: t('text.trading.symbolMgmt.baseAsset'),
      dataIndex: 'baseAsset',
      key: 'baseAsset',
      width: 100,
    },
    {
      title: t('text.trading.symbolMgmt.quoteAsset'),
      dataIndex: 'quoteAsset',
      key: 'quoteAsset',
      width: 100,
    },
    {
      title: t('text.trading.symbolMgmt.marketType'),
      dataIndex: 'marketType',
      key: 'marketType',
      width: 100,
      render: (v: string) => {
        const colorMap: Record<string, string> = { SPOT: 'blue', USDM: 'green', COINM: 'orange' };
        return <Tag color={colorMap[v] ?? 'default'}>{v}</Tag>;
      },
    },
    {
      title: t('text.trading.symbolMgmt.exchangeSymbol'),
      dataIndex: 'exchangeSymbol',
      key: 'exchangeSymbol',
      width: 160,
    },
    {
      title: t('text.trading.symbolMgmt.lotSize'),
      dataIndex: 'lotSize',
      key: 'lotSize',
      width: 120,
      render: (v: string) => v ?? '-',
    },
    {
      title: t('text.trading.symbolMgmt.tickSize'),
      dataIndex: 'tickSize',
      key: 'tickSize',
      width: 120,
      render: (v: string) => v ?? '-',
    },
    {
      title: t('common.status'),
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (v: string) => (
        <Tag color={v === 'TRADING' ? 'success' : 'default'}>{v}</Tag>
      ),
    },
  ];

  return (
    <div>
      {/* 工具栏 */}
      <Space style={{ marginBottom: 16 }} wrap>
        <Select
          allowClear
          placeholder={t('text.trading.symbolMgmt.allMarkets')}
          style={{ width: 140 }}
          options={MARKET_TYPE_OPTIONS}
          value={marketType}
          onChange={(v) => setMarketType(v)}
        />
        <Button
          icon={<ReloadOutlined />}
          onClick={() => fetchSymbols(marketType)}
          loading={loading}
        >
          {t('text.quote.refresh')}
        </Button>
        <Button
          type="primary"
          icon={<SyncOutlined />}
          loading={syncing}
          onClick={handleSync}
        >
          {t('text.trading.symbolMgmt.sync')}
        </Button>
      </Space>

      <Table
        rowKey="id"
        loading={loading}
        dataSource={data}
        columns={columns}
        size="small"
        pagination={{ pageSize: 50, showSizeChanger: true, pageSizeOptions: ['50', '100', '200'] }}
        scroll={{ x: 900 }}
      />
    </div>
  );
};
