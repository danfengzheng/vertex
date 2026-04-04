import { useState, useCallback } from 'react';
import { Table, Button, Form, Select, Input, Space, message, Tag } from 'antd';
import { SearchOutlined, ReloadOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import {
  klineApi,
  KLineVO,
  KLineQueryDTO,
  KLINE_INTERVAL_LABELS,
} from '../../api/quote';
import { symbolApi, ExchangeSymbolVO } from '../../api/symbol';
import { formatTimestamp } from '../../utils/date';

const INTERVAL_OPTIONS = Object.entries(KLINE_INTERVAL_LABELS).map(([value, label]) => ({
  value,
  label,
}));

const EXCHANGE_OPTIONS = [
  { value: 'binance', label: 'Binance' },
  { value: 'okx', label: 'OKX' },
];

export const KLineQuery = () => {
  const { t } = useTranslation();
  const [klines, setKlines] = useState<KLineVO[]>([]);
  const [loading, setLoading] = useState(false);
  const [form] = Form.useForm();
  const [symbolOptions, setSymbolOptions] = useState<{ label: string; value: string }[]>([]);
  const [symbolsLoading, setSymbolsLoading] = useState(false);

  const fetchSymbolsByExchange = useCallback(async (exchange: string) => {
    if (!exchange) { setSymbolOptions([]); return; }
    setSymbolsLoading(true);
    try {
      const res = await symbolApi.list(exchange);
      if (res.data) {
        const seen = new Set<string>();
        const opts = (res.data as ExchangeSymbolVO[])
          .filter((s) => !seen.has(s.symbol) && seen.add(s.symbol))
          .map((s) => ({ label: s.symbol, value: s.symbol }));
        setSymbolOptions(opts);
      }
    } catch {
      // error handled by interceptor
    } finally {
      setSymbolsLoading(false);
    }
  }, []);

  const handleQuery = async () => {
    try {
      const values = await form.validateFields();
      setLoading(true);
      const query: KLineQueryDTO = {
        symbol: values.symbol,
        exchange: values.exchange,
        interval: values.interval,
        limit: values.limit ? Number(values.limit) : 500,
      };
      const response = await klineApi.query(query);
      if (response.code === 200) {
        setKlines(response.data || []);
        if (!response.data || response.data.length === 0) {
          message.info(t('message.quote.noData'));
        }
      }
    } catch {
      // form validation error
    } finally {
      setLoading(false);
    }
  };

  const handleReset = () => {
    form.resetFields();
    setKlines([]);
    setSymbolOptions([]);
  };

  const columns = [
    {
      title: t('text.quote.openTime'),
      dataIndex: 'openTime',
      key: 'openTime',
      width: 180,
      render: (val: number | string) => formatTimestamp(val),
    },
    {
      title: t('text.quote.open'),
      dataIndex: 'open',
      key: 'open',
      align: 'right' as const,
    },
    {
      title: t('text.quote.high'),
      dataIndex: 'high',
      key: 'high',
      align: 'right' as const,
      render: (val: string) => <span style={{ color: '#52c41a' }}>{val}</span>,
    },
    {
      title: t('text.quote.low'),
      dataIndex: 'low',
      key: 'low',
      align: 'right' as const,
      render: (val: string) => <span style={{ color: '#ff4d4f' }}>{val}</span>,
    },
    {
      title: t('text.quote.close'),
      dataIndex: 'close',
      key: 'close',
      align: 'right' as const,
      render: (val: string, record: KLineVO) => {
        const isUp = Number(val) >= Number(record.open);
        return <span style={{ color: isUp ? '#52c41a' : '#ff4d4f', fontWeight: 600 }}>{val}</span>;
      },
    },
    {
      title: t('text.quote.volume'),
      dataIndex: 'volume',
      key: 'volume',
      align: 'right' as const,
    },
    {
      title: t('text.quote.trades'),
      dataIndex: 'trades',
      key: 'trades',
      align: 'right' as const,
    },
    {
      title: t('text.quote.closed'),
      dataIndex: 'closed',
      key: 'closed',
      align: 'center' as const,
      render: (val: boolean) =>
        val ? (
          <Tag color="success">{t('text.quote.closedYes')}</Tag>
        ) : (
          <Tag color="processing">{t('text.quote.closedNo')}</Tag>
        ),
    },
  ];

  return (
    <div>
      <div style={{ marginBottom: 16 }}>
        <h2 style={{ margin: 0, fontSize: 20, fontWeight: 600 }}>{t('text.quote.klineTitle')}</h2>
      </div>

      {/* 查询表单 */}
      <Form form={form} layout="inline" style={{ marginBottom: 16, flexWrap: 'wrap', gap: 8 }}>
        <Form.Item
          name="exchange"
          rules={[{ required: true, message: t('placeholder.quote.exchange') }]}
        >
          <Select
            placeholder={t('placeholder.quote.exchange')}
            options={EXCHANGE_OPTIONS}
            style={{ width: 140 }}
            onChange={(val: string) => {
              form.setFieldValue('symbol', undefined);
              fetchSymbolsByExchange(val);
            }}
          />
        </Form.Item>
        <Form.Item
          name="symbol"
          rules={[{ required: true, message: t('placeholder.quote.symbol') }]}
        >
          <Select
            showSearch
            placeholder={t('placeholder.quote.symbol')}
            style={{ width: 180 }}
            loading={symbolsLoading}
            options={symbolOptions}
            filterOption={(input, option) =>
              (option?.value as string ?? '').toLowerCase().includes(input.toLowerCase())
            }
            notFoundContent={symbolOptions.length === 0 ? t('placeholder.quote.exchange') : undefined}
          />
        </Form.Item>
        <Form.Item
          name="interval"
          rules={[{ required: true, message: t('placeholder.quote.interval') }]}
        >
          <Select
            placeholder={t('placeholder.quote.interval')}
            options={INTERVAL_OPTIONS}
            style={{ width: 120 }}
          />
        </Form.Item>
        <Form.Item name="limit">
          <Input type="number" placeholder={t('placeholder.quote.limit')} style={{ width: 120 }} />
        </Form.Item>
        <Form.Item>
          <Space>
            <Button type="primary" icon={<SearchOutlined />} onClick={handleQuery}>
              {t('common.search')}
            </Button>
            <Button icon={<ReloadOutlined />} onClick={handleReset}>
              {t('common.reset')}
            </Button>
          </Space>
        </Form.Item>
      </Form>

      <Table
        columns={columns}
        dataSource={klines}
        loading={loading}
        rowKey={(record) => `${record.openTime}-${record.symbol}`}
        pagination={{
          showSizeChanger: true,
          showTotal: (total) => t('common.total', { total }),
        }}
        scroll={{ x: 900 }}
      />
    </div>
  );
};
