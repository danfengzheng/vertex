import { useState, useEffect, useRef, useCallback } from 'react';
import { Table, Button, Space, message, Tag, Modal, Form, Select, Input, Card, Row, Col, DatePicker, Progress, Alert } from 'antd';
import type { Dayjs } from 'dayjs';
import dayjs from 'dayjs';
import {
  PlayCircleOutlined,
  PauseCircleOutlined,
  PlusOutlined,
  MinusCircleOutlined,
  HistoryOutlined,
  ReloadOutlined,
  SyncOutlined,
  LoadingOutlined,
  CheckCircleOutlined,
  CloseCircleOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import {
  quoteSourceApi,
  DataSourceStatusVO,
  SubscribeRequestDTO,
  SubscriptionItem,
  KLineQueryDTO,
  KLineInterval,
  KLINE_INTERVAL_LABELS,
  BackfillProgressVO,
} from '../../api/quote';

const INTERVAL_OPTIONS = Object.entries(KLINE_INTERVAL_LABELS).map(([value, label]) => ({
  value,
  label,
}));

/** 补全任务信息 */
interface BackfillTask {
  taskId: string;
  exchange: string;
  symbol: string;
  progress: BackfillProgressVO;
}

export const DataSourceManagement = () => {
  const { t } = useTranslation();
  const [dataSources, setDataSources] = useState<DataSourceStatusVO[]>([]);
  const [loading, setLoading] = useState(false);
  const [actionLoading, setActionLoading] = useState<string | null>(null);

  // 订阅弹窗
  const [subscribeVisible, setSubscribeVisible] = useState(false);
  const [subscribeExchange, setSubscribeExchange] = useState('');
  const [subscribeForm] = Form.useForm();

  // 取消订阅弹窗
  const [unsubVisible, setUnsubVisible] = useState(false);
  const [unsubExchange, setUnsubExchange] = useState('');
  const [unsubLoading, setUnsubLoading] = useState(false);
  const [subscriptionList, setSubscriptionList] = useState<SubscriptionItem[]>([]);
  const [subscriptionListLoading, setSubscriptionListLoading] = useState(false);

  // 补全弹窗
  const [backfillVisible, setBackfillVisible] = useState(false);
  const [backfillExchange, setBackfillExchange] = useState('');
  const [backfillLoading, setBackfillLoading] = useState(false);
  const [backfillForm] = Form.useForm();

  // 补全任务进度追踪
  const [backfillTasks, setBackfillTasks] = useState<BackfillTask[]>([]);
  const pollingTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);

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

  // 轮询进度
  const pollProgress = useCallback(async () => {
    setBackfillTasks((prevTasks) => {
      const runningTasks = prevTasks.filter(
        (task) => task.progress.status === 'running'
      );

      if (runningTasks.length === 0) return prevTasks;

      // 异步更新每个运行中的任务
      runningTasks.forEach(async (task) => {
        try {
          const response = await quoteSourceApi.backfillProgress(task.taskId);
          if (response.code === 200) {
            setBackfillTasks((current) =>
              current.map((t) =>
                t.taskId === task.taskId
                  ? { ...t, progress: response.data }
                  : t
              )
            );

            // 任务完成/失败时提示
            if (response.data.status === 'done') {
              message.success(
                t('message.quote.backfillDone', {
                  symbol: task.symbol,
                  count: response.data.completedRecords ?? 0,
                })
              );
            } else if (response.data.status === 'error') {
              message.error(
                t('message.quote.backfillError', {
                  symbol: task.symbol,
                  error: response.data.errorMessage ?? '',
                })
              );
            }
          }
        } catch {
          // 网络错误静默处理，下次轮询重试
        }
      });

      return prevTasks;
    });
  }, [t]);

  // 启动/停止轮询
  useEffect(() => {
    const hasRunning = backfillTasks.some(
      (task) => task.progress.status === 'running'
    );

    if (hasRunning && !pollingTimerRef.current) {
      pollingTimerRef.current = setInterval(pollProgress, 2000);
    } else if (!hasRunning && pollingTimerRef.current) {
      clearInterval(pollingTimerRef.current);
      pollingTimerRef.current = null;
    }

    return () => {
      if (pollingTimerRef.current) {
        clearInterval(pollingTimerRef.current);
        pollingTimerRef.current = null;
      }
    };
  }, [backfillTasks, pollProgress]);

  useEffect(() => {
    loadStatus();
  }, []);

  // 清除已完成的任务
  const dismissTask = (taskId: string) => {
    setBackfillTasks((prev) => prev.filter((t) => t.taskId !== taskId));
  };

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

  const openSubscribe = (exchange: string) => {
    setSubscribeExchange(exchange);
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
      await quoteSourceApi.subscribe(data);
      message.success(t('message.quote.subscribeSuccess'));
      setSubscribeVisible(false);
    } catch {
      // form validation error
    }
  };

  /** 打开取消订阅弹窗，自动加载当前订阅列表 */
  const openUnsubscribe = async (exchange: string) => {
    setUnsubExchange(exchange);
    setSubscriptionList([]);
    setUnsubVisible(true);
    setSubscriptionListLoading(true);
    try {
      const response = await quoteSourceApi.subscriptions(exchange);
      if (response.code === 200) {
        setSubscriptionList(response.data ?? []);
      }
    } catch {
      message.error(t('message.quote.loadFailed'));
    } finally {
      setSubscriptionListLoading(false);
    }
  };

  /** 取消订阅单条记录 */
  const handleUnsubscribeItem = async (item: SubscriptionItem) => {
    setUnsubLoading(true);
    try {
      await quoteSourceApi.unsubscribe({
        exchange: unsubExchange,
        symbol: item.symbol,
        interval: item.interval as KLineInterval,
      });
      message.success(t('message.quote.unsubscribeSuccess'));
      // 从列表中移除
      setSubscriptionList((prev) =>
        prev.filter((s) => !(s.symbol === item.symbol && s.interval === item.interval))
      );
    } catch {
      message.error(t('message.quote.loadFailed'));
    } finally {
      setUnsubLoading(false);
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
      const timeRange: [Dayjs, Dayjs] | undefined = values.timeRange;
      const data: KLineQueryDTO = {
        exchange: backfillExchange,
        symbol: values.symbol,
        interval: values.interval === 'ALL' ? null : values.interval,
        startTime: timeRange ? timeRange[0].valueOf() : undefined,
        endTime: timeRange ? timeRange[1].valueOf() : undefined,
      };
      setBackfillLoading(true);
      const response = await quoteSourceApi.backfill(data);
      if (response.code === 200) {
        const taskId = response.data;
        // 添加任务到追踪列表
        setBackfillTasks((prev) => [
          ...prev,
          {
            taskId,
            exchange: backfillExchange.toUpperCase(),
            symbol: values.symbol,
            progress: {
              status: 'running',
              totalIntervals: 0,
              completedIntervals: 0,
              completedRecords: 0,
              currentInterval: '',
            },
          },
        ]);
        message.info(t('message.quote.backfillSubmitted', { symbol: values.symbol }));
      }
      setBackfillVisible(false);
    } catch {
      // form validation error
    } finally {
      setBackfillLoading(false);
    }
  };

  /** 快捷时间范围 preset */
  const timePresets = [
    { label: t('text.quote.preset1w'), value: 7 },
    { label: t('text.quote.preset1m'), value: 30 },
    { label: t('text.quote.preset3m'), value: 90 },
    { label: t('text.quote.preset1y'), value: 365 },
  ];

  const handleTimePreset = (days: number) => {
    const end = dayjs();
    const start = end.subtract(days, 'day');
    backfillForm.setFieldsValue({
      timeRange: [start, end],
    });
  };

  /** 渲染任务状态图标 */
  const renderTaskStatusIcon = (status: string) => {
    switch (status) {
      case 'running':
        return <LoadingOutlined spin style={{ color: '#1677ff' }} />;
      case 'done':
        return <CheckCircleOutlined style={{ color: '#52c41a' }} />;
      case 'error':
        return <CloseCircleOutlined style={{ color: '#ff4d4f' }} />;
      default:
        return null;
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
            onClick={() => openSubscribe(record.exchange)}
          >
            {t('text.quote.subscribe')}
          </Button>
          <Button
            type="link"
            icon={<MinusCircleOutlined />}
            disabled={!record.connected}
            onClick={() => openUnsubscribe(record.exchange)}
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

      {/* 补全任务进度面板 */}
      {backfillTasks.length > 0 && (
        <Card
          size="small"
          title={t('text.quote.backfillTasks')}
          style={{ marginBottom: 16 }}
        >
          <Space direction="vertical" style={{ width: '100%' }} size="middle">
            {backfillTasks.map((task) => {
              const { progress } = task;
              const percent =
                progress.totalIntervals && progress.totalIntervals > 0
                  ? Math.round(
                      ((progress.completedIntervals ?? 0) / progress.totalIntervals) * 100
                    )
                  : 0;
              const isFinished = progress.status === 'done' || progress.status === 'error';

              return (
                <div key={task.taskId}>
                  <div
                    style={{
                      display: 'flex',
                      justifyContent: 'space-between',
                      alignItems: 'center',
                      marginBottom: 4,
                    }}
                  >
                    <Space>
                      {renderTaskStatusIcon(progress.status)}
                      <span style={{ fontWeight: 600 }}>
                        {task.exchange} - {task.symbol}
                      </span>
                      {progress.status === 'running' && progress.currentInterval && (
                        <Tag color="processing">{progress.currentInterval}</Tag>
                      )}
                    </Space>
                    <Space>
                      <span style={{ fontSize: 12, color: '#999' }}>
                        {t('text.quote.backfillRecords', {
                          count: progress.completedRecords ?? 0,
                        })}
                      </span>
                      {isFinished && (
                        <Button
                          type="link"
                          size="small"
                          onClick={() => dismissTask(task.taskId)}
                        >
                          {t('common.confirm')}
                        </Button>
                      )}
                    </Space>
                  </div>
                  <Progress
                    percent={percent}
                    status={
                      progress.status === 'error'
                        ? 'exception'
                        : progress.status === 'done'
                        ? 'success'
                        : 'active'
                    }
                    format={() =>
                      `${progress.completedIntervals ?? 0}/${progress.totalIntervals ?? 0}`
                    }
                    size="small"
                  />
                  {progress.status === 'error' && progress.errorMessage && (
                    <Alert
                      message={progress.errorMessage}
                      type="error"
                      showIcon
                      style={{ marginTop: 4 }}
                      closable={false}
                    />
                  )}
                </div>
              );
            })}
          </Space>
        </Card>
      )}

      <Table
        columns={columns}
        dataSource={dataSources}
        loading={loading}
        rowKey="exchange"
        pagination={false}
      />

      {/* 订阅弹窗 */}
      <Modal
        title={t('text.quote.subscribe') + ' - ' + subscribeExchange.toUpperCase()}
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

      {/* 取消订阅弹窗 */}
      <Modal
        title={t('text.quote.unsubscribe') + ' - ' + unsubExchange.toUpperCase()}
        open={unsubVisible}
        footer={null}
        onCancel={() => setUnsubVisible(false)}
      >
        {subscriptionListLoading ? (
          <div style={{ textAlign: 'center', padding: '24px 0' }}>
            <LoadingOutlined spin style={{ fontSize: 24 }} />
          </div>
        ) : subscriptionList.length === 0 ? (
          <Alert
            message={t('text.quote.noSubscriptions')}
            type="info"
            showIcon
            style={{ margin: '12px 0' }}
          />
        ) : (
          <Table
            dataSource={subscriptionList}
            rowKey={(r) => `${r.symbol}_${r.interval}`}
            pagination={false}
            size="small"
            columns={[
              {
                title: t('text.quote.symbol'),
                dataIndex: 'symbol',
                key: 'symbol',
                render: (text: string) => <span style={{ fontWeight: 600 }}>{text}</span>,
              },
              {
                title: t('text.quote.interval'),
                dataIndex: 'interval',
                key: 'interval',
                render: (val: string) => (
                  <Tag color="blue">
                    {KLINE_INTERVAL_LABELS[val as KLineInterval] || val}
                  </Tag>
                ),
              },
              {
                title: t('common.operation'),
                key: 'action',
                width: 100,
                render: (_: unknown, record: SubscriptionItem) => (
                  <Button
                    type="link"
                    danger
                    size="small"
                    loading={unsubLoading}
                    icon={<MinusCircleOutlined />}
                    onClick={() => handleUnsubscribeItem(record)}
                  >
                    {t('text.quote.unsubscribe')}
                  </Button>
                ),
              },
            ]}
          />
        )}
      </Modal>

      {/* 历史补全弹窗 */}
      <Modal
        title={t('text.quote.backfill') + ' - ' + backfillExchange.toUpperCase()}
        open={backfillVisible}
        onOk={handleBackfillSubmit}
        confirmLoading={backfillLoading}
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
            <Select
              placeholder={t('placeholder.quote.interval')}
              options={[
                { value: 'ALL', label: t('text.quote.intervalAll') },
                ...INTERVAL_OPTIONS,
              ]}
            />
          </Form.Item>
          <Form.Item label={t('text.quote.timeRange')}>
            <Space style={{ marginBottom: 8 }} wrap>
              {timePresets.map((preset) => (
                <Button
                  key={preset.value}
                  size="small"
                  onClick={() => handleTimePreset(preset.value)}
                >
                  {preset.label}
                </Button>
              ))}
            </Space>
            <Form.Item name="timeRange" noStyle>
              <DatePicker.RangePicker
                showTime
                style={{ width: '100%' }}
                placeholder={[t('placeholder.quote.startTime'), t('placeholder.quote.endTime')]}
              />
            </Form.Item>
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};
