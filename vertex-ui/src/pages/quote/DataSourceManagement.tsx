import { useState, useEffect } from 'react';
import { Table, Button, Space, message, Tag, Modal, Form, Select, Input, Card, Row, Col } from 'antd';
import {
  PlayCircleOutlined,
  PauseCircleOutlined,
  PlusOutlined,
  MinusCircleOutlined,
  HistoryOutlined,
  ReloadOutlined,
  SyncOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import {
  quoteSourceApi,
  DataSourceStatusVO,
  SubscribeRequestDTO,
  KLineQueryDTO,
  KLineInterval,
  KLINE_INTERVAL_LABELS,
} from '../../api/quote';

const INTERVAL_OPTIONS = Object.entries(KLINE_INTERVAL_LABELS).map(([value, label]) => ({
  value,
  label,
}));

export const DataSourceManagement = () => {
  const { t } = useTranslation();
  const [dataSources, setDataSources] = useState<DataSourceStatusVO[]>([]);
  const [loading, setLoading] = useState(false);
  const [actionLoading, setActionLoading] = useState<string | null>(null);

  // 订阅弹窗
  const [subscribeVisible, setSubscribeVisible] = useState(false);
  const [subscribeType, setSubscribeType] = useState<'subscribe' | 'unsubscribe'>('subscribe');
  const [subscribeExchange, setSubscribeExchange] = useState('');
  const [subscribeForm] = Form.useForm();

  // 补全弹窗
  const [backfillVisible, setBackfillVisible] = useState(false);
  const [backfillExchange, setBackfillExchange] = useState('');
  const [backfillForm] = Form.useForm();

  const loadStatus = async () => {
    setLoading(true);
    try {
      const response = await quoteSourceApi.status();
      if (response.code === 200) {
        setDataSources(response.data);
      }
    } catch {
      message.error(t('message.quote.loadFailed'));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadStatus();
  }, []);

  const handleStart = async (exchange: string) => {
    setActionLoading(exchange);
    try {
      await quoteSourceApi.start(exchange);
      message.success(t('message.quote.startSuccess'));
      loadStatus();
    } catch {
      message.error(t('message.quote.startFailed'));
    } finally {
      setActionLoading(null);
    }
  };

  const handleStop = async (exchange: string) => {
    setActionLoading(exchange);
    try {
      await quoteSourceApi.stop(exchange);
      message.success(t('message.quote.stopSuccess'));
      loadStatus();
    } catch {
      message.error(t('message.quote.stopFailed'));
    } finally {
      setActionLoading(null);
    }
  };

  const openSubscribe = (exchange: string, type: 'subscribe' | 'unsubscribe') => {
    setSubscribeExchange(exchange);
    setSubscribeType(type);
    subscribeForm.resetFields();
    setSubscribeVisible(true);
  };

  const handleSubscribeSubmit = async () => {
    try {
      const values = await subscribeForm.validateFields();
      const data: SubscribeRequestDTO = {
        exchange: subscribeExchange,
        symbol: values.symbol,
        interval: values.interval,
      };
      if (subscribeType === 'subscribe') {
        await quoteSourceApi.subscribe(data);
        message.success(t('message.quote.subscribeSuccess'));
      } else {
        await quoteSourceApi.unsubscribe(data);
        message.success(t('message.quote.unsubscribeSuccess'));
      }
      setSubscribeVisible(false);
    } catch {
      // form validation error
    }
  };

  const openBackfill = (exchange: string) => {
    setBackfillExchange(exchange);
    backfillForm.resetFields();
    setBackfillVisible(true);
  };

  const handleBackfillSubmit = async () => {
    try {
      const values = await backfillForm.validateFields();
      const data: KLineQueryDTO = {
        exchange: backfillExchange,
        symbol: values.symbol,
        interval: values.interval,
        limit: values.limit || 500,
      };
      const response = await quoteSourceApi.backfill(data);
      if (response.code === 200) {
        message.success(t('message.quote.backfillSuccess', { count: response.data }));
      }
      setBackfillVisible(false);
    } catch {
      // form validation error
    }
  };

  const columns = [
    {
      title: t('text.quote.exchange'),
      dataIndex: 'exchange',
      key: 'exchange',
      render: (text: string) => <span style={{ fontWeight: 600, textTransform: 'uppercase' as const }}>{text}</span>,
    },
    {
      title: t('common.status'),
      dataIndex: 'connected',
      key: 'connected',
      render: (connected: boolean) =>
        connected ? (
          <Tag icon={<SyncOutlined spin />} color="success">{t('text.quote.connected')}</Tag>
        ) : (
          <Tag color="default">{t('text.quote.disconnected')}</Tag>
        ),
    },
    {
      title: t('common.operation'),
      key: 'action',
      render: (_: unknown, record: DataSourceStatusVO) => (
        <Space wrap>
          {record.connected ? (
            <Button
              type="link"
              danger
              icon={<PauseCircleOutlined />}
              loading={actionLoading === record.exchange}
              onClick={() => handleStop(record.exchange)}
            >
              {t('text.quote.stop')}
            </Button>
          ) : (
            <Button
              type="link"
              icon={<PlayCircleOutlined />}
              loading={actionLoading === record.exchange}
              onClick={() => handleStart(record.exchange)}
            >
              {t('text.quote.start')}
            </Button>
          )}
          <Button
            type="link"
            icon={<PlusOutlined />}
            disabled={!record.connected}
            onClick={() => openSubscribe(record.exchange, 'subscribe')}
          >
            {t('text.quote.subscribe')}
          </Button>
          <Button
            type="link"
            icon={<MinusCircleOutlined />}
            disabled={!record.connected}
            onClick={() => openSubscribe(record.exchange, 'unsubscribe')}
          >
            {t('text.quote.unsubscribe')}
          </Button>
          <Button
            type="link"
            icon={<HistoryOutlined />}
            onClick={() => openBackfill(record.exchange)}
          >
            {t('text.quote.backfill')}
          </Button>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h2 style={{ margin: 0, fontSize: 20, fontWeight: 600 }}>{t('text.quote.sourceTitle')}</h2>
        <Button icon={<ReloadOutlined />} onClick={loadStatus}>
          {t('text.quote.refresh')}
        </Button>
      </div>

      {/* 状态卡片概览 */}
      <Row gutter={16} style={{ marginBottom: 16 }}>
        {dataSources.map((ds) => (
          <Col key={ds.exchange} xs={24} sm={12} md={8} lg={6}>
            <Card size="small">
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <span style={{ fontWeight: 600, textTransform: 'uppercase' }}>{ds.exchange}</span>
                {ds.connected ? (
                  <Tag color="success">{t('text.quote.connected')}</Tag>
                ) : (
                  <Tag color="default">{t('text.quote.disconnected')}</Tag>
                )}
              </div>
            </Card>
          </Col>
        ))}
      </Row>

      <Table
        columns={columns}
        dataSource={dataSources}
        loading={loading}
        rowKey="exchange"
        pagination={false}
      />

      {/* 订阅/取消订阅弹窗 */}
      <Modal
        title={
          subscribeType === 'subscribe'
            ? t('text.quote.subscribe') + ' - ' + subscribeExchange.toUpperCase()
            : t('text.quote.unsubscribe') + ' - ' + subscribeExchange.toUpperCase()
        }
        open={subscribeVisible}
        onOk={handleSubscribeSubmit}
        onCancel={() => setSubscribeVisible(false)}
      >
        <Form form={subscribeForm} layout="vertical">
          <Form.Item
            name="symbol"
            label={t('text.quote.symbol')}
            rules={[{ required: true, message: t('placeholder.quote.symbol') }]}
          >
            <Input placeholder={t('placeholder.quote.symbol')} />
          </Form.Item>
          <Form.Item
            name="interval"
            label={t('text.quote.interval')}
            rules={[{ required: true, message: t('placeholder.quote.interval') }]}
          >
            <Select placeholder={t('placeholder.quote.interval')} options={INTERVAL_OPTIONS} />
          </Form.Item>
        </Form>
      </Modal>

      {/* 历史补全弹窗 */}
      <Modal
        title={t('text.quote.backfill') + ' - ' + backfillExchange.toUpperCase()}
        open={backfillVisible}
        onOk={handleBackfillSubmit}
        onCancel={() => setBackfillVisible(false)}
      >
        <Form form={backfillForm} layout="vertical">
          <Form.Item
            name="symbol"
            label={t('text.quote.symbol')}
            rules={[{ required: true, message: t('placeholder.quote.symbol') }]}
          >
            <Input placeholder={t('placeholder.quote.symbol')} />
          </Form.Item>
          <Form.Item
            name="interval"
            label={t('text.quote.interval')}
            rules={[{ required: true, message: t('placeholder.quote.interval') }]}
          >
            <Select placeholder={t('placeholder.quote.interval')} options={INTERVAL_OPTIONS} />
          </Form.Item>
          <Form.Item name="limit" label={t('text.quote.limit')}>
            <Input type="number" placeholder="500" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};
