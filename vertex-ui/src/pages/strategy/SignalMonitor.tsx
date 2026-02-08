import { useState, useEffect } from 'react';
import {
  Table, Button, Space, message, Tag, Select, Input, DatePicker, Modal, Descriptions,
  Progress,
} from 'antd';
import type { Dayjs } from 'dayjs';
import dayjs from 'dayjs';
import {
  ReloadOutlined,
  ThunderboltOutlined,
  EyeOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import {
  signalApi,
  strategyApi,
  SignalVO,
  SignalType,
  StrategyVO,
} from '../../api/strategy';
import { KLINE_INTERVAL_LABELS, KLineInterval } from '../../api/quote';

const INTERVAL_OPTIONS = Object.entries(KLINE_INTERVAL_LABELS).map(([value, label]) => ({
  value,
  label,
}));

const SIGNAL_TYPE_OPTIONS = [
  { value: 'BUY', label: '买入' },
  { value: 'SELL', label: '卖出' },
  { value: 'NEUTRAL', label: '中性' },
];

const signalTypeColor: Record<SignalType, string> = {
  BUY: 'success',
  SELL: 'error',
  NEUTRAL: 'default',
};

const signalTypeLabel: Record<SignalType, string> = {
  BUY: '买入',
  SELL: '卖出',
  NEUTRAL: '中性',
};

export const SignalMonitor = () => {
  const { t } = useTranslation();
  const [signals, setSignals] = useState<SignalVO[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(10);

  // 筛选
  const [exchange, setExchange] = useState<string | undefined>();
  const [symbol, setSymbol] = useState<string | undefined>();
  const [interval, setInterval] = useState<KLineInterval | undefined>();
  const [signalType, setSignalType] = useState<SignalType | undefined>();
  const [timeRange, setTimeRange] = useState<[Dayjs, Dayjs] | null>(null);
  const [strategyId, setStrategyId] = useState<number | undefined>();

  // 策略列表（用于手动分析按钮）
  const [strategies, setStrategies] = useState<StrategyVO[]>([]);

  // 信号详情弹窗
  const [detailVisible, setDetailVisible] = useState(false);
  const [detailSignal, setDetailSignal] = useState<SignalVO | null>(null);

  const loadStrategies = async () => {
    try {
      const response = await strategyApi.page({ pageNum: 1, pageSize: 100 });
      if (response.code === 200) {
        setStrategies(response.data.records);
      }
    } catch {
      // silent
    }
  };

  const loadData = async () => {
    setLoading(true);
    try {
      const response = await signalApi.page({
        pageNum,
        pageSize,
        exchange,
        symbol,
        interval,
        signalType,
        strategyId,
        startTime: timeRange?.[0]?.valueOf(),
        endTime: timeRange?.[1]?.valueOf(),
      });
      if (response.code === 200) {
        setSignals(response.data.records);
        setTotal(response.data.total);
      }
    } catch {
      message.error(t('message.strategy.loadFailed'));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadStrategies();
  }, []);

  useEffect(() => {
    loadData();
  }, [pageNum, pageSize]);

  const handleSearch = () => {
    setPageNum(1);
    loadData();
  };

  const handleAnalyze = async (strategyIdToAnalyze: number) => {
    try {
      await signalApi.analyze(strategyIdToAnalyze);
      message.success(t('message.strategy.analyzeSubmitted'));
      setTimeout(loadData, 1000);
    } catch {
      message.error(t('message.strategy.loadFailed'));
    }
  };

  const showDetail = (record: SignalVO) => {
    setDetailSignal(record);
    setDetailVisible(true);
  };

  const columns = [
    {
      title: t('text.strategy.signalTime'),
      dataIndex: 'signalTime',
      key: 'signalTime',
      width: 180,
      render: (val: number) => dayjs(val).format('YYYY-MM-DD HH:mm:ss'),
    },
    {
      title: t('text.strategy.name'),
      dataIndex: 'strategyName',
      key: 'strategyName',
      render: (text: string) => <span style={{ fontWeight: 600 }}>{text}</span>,
    },
    {
      title: t('text.strategy.exchange'),
      dataIndex: 'exchange',
      key: 'exchange',
      render: (text: string) => <span style={{ textTransform: 'uppercase' as const }}>{text}</span>,
    },
    {
      title: t('text.strategy.symbol'),
      dataIndex: 'symbol',
      key: 'symbol',
    },
    {
      title: t('text.strategy.interval'),
      dataIndex: 'interval',
      key: 'interval',
      render: (val: KLineInterval) => KLINE_INTERVAL_LABELS[val] || val,
    },
    {
      title: t('text.strategy.signalType'),
      dataIndex: 'signalType',
      key: 'signalType',
      render: (val: SignalType) => (
        <Tag color={signalTypeColor[val]}>{signalTypeLabel[val]}</Tag>
      ),
    },
    {
      title: t('text.strategy.signalStrength'),
      dataIndex: 'signalStrength',
      key: 'signalStrength',
      width: 120,
      render: (val: number, record: SignalVO) => (
        <Progress
          percent={val}
          size="small"
          status={record.signalType === 'BUY' ? 'success' : record.signalType === 'SELL' ? 'exception' : 'normal'}
          format={(p) => `${p}%`}
        />
      ),
    },
    {
      title: t('text.strategy.price'),
      dataIndex: 'price',
      key: 'price',
    },
    {
      title: t('common.operation'),
      key: 'action',
      width: 80,
      render: (_: unknown, record: SignalVO) => (
        <Button type="link" icon={<EyeOutlined />} onClick={() => showDetail(record)}>
          {t('text.strategy.detail')}
        </Button>
      ),
    },
  ];

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h2 style={{ margin: 0, fontSize: 20, fontWeight: 600 }}>{t('text.strategy.signalTitle')}</h2>
        <Button icon={<ReloadOutlined />} onClick={loadData}>
          {t('text.quote.refresh')}
        </Button>
      </div>

      {/* 筛选栏 */}
      <Space wrap style={{ marginBottom: 16 }}>
        <Select
          allowClear
          placeholder={t('placeholder.strategy.exchange')}
          style={{ width: 140 }}
          value={exchange}
          onChange={setExchange}
        >
          <Select.Option value="binance">Binance</Select.Option>
          <Select.Option value="okx">OKX</Select.Option>
        </Select>

        <Input
          allowClear
          placeholder={t('placeholder.strategy.symbol')}
          style={{ width: 160 }}
          value={symbol}
          onChange={(e) => setSymbol(e.target.value || undefined)}
        />

        <Select
          allowClear
          placeholder={t('placeholder.strategy.interval')}
          style={{ width: 100 }}
          options={INTERVAL_OPTIONS}
          value={interval}
          onChange={setInterval}
        />

        <Select
          allowClear
          placeholder={t('text.strategy.signalType')}
          style={{ width: 100 }}
          options={SIGNAL_TYPE_OPTIONS}
          value={signalType}
          onChange={setSignalType}
        />

        <Select
          allowClear
          placeholder={t('text.strategy.name')}
          style={{ width: 200 }}
          value={strategyId}
          onChange={setStrategyId}
        >
          {strategies.map((s) => (
            <Select.Option key={s.id} value={s.id}>{s.name}</Select.Option>
          ))}
        </Select>

        <DatePicker.RangePicker
          showTime
          value={timeRange}
          onChange={(vals) => setTimeRange(vals as [Dayjs, Dayjs] | null)}
        />

        <Button type="primary" onClick={handleSearch}>
          {t('common.search')}
        </Button>
      </Space>

      {/* 手动分析按钮组 */}
      {strategies.filter((s) => s.enabled === 1).length > 0 && (
        <Space wrap style={{ marginBottom: 16 }}>
          <span style={{ fontSize: 12, color: '#999' }}>{t('text.strategy.manualAnalysis')}:</span>
          {strategies
            .filter((s) => s.enabled === 1)
            .map((s) => (
              <Button
                key={s.id}
                size="small"
                icon={<ThunderboltOutlined />}
                onClick={() => handleAnalyze(s.id)}
              >
                {s.name}
              </Button>
            ))}
        </Space>
      )}

      <Table
        columns={columns}
        dataSource={signals}
        loading={loading}
        rowKey="id"
        pagination={{
          current: pageNum,
          pageSize,
          total,
          showTotal: (t) => `共 ${t} 条`,
          onChange: (p, s) => { setPageNum(p); setPageSize(s); },
        }}
      />

      {/* 信号详情弹窗 */}
      <Modal
        title={t('text.strategy.signalDetail')}
        open={detailVisible}
        onCancel={() => setDetailVisible(false)}
        footer={null}
        width={640}
      >
        {detailSignal && (
          <Descriptions bordered column={2} size="small">
            <Descriptions.Item label={t('text.strategy.name')}>
              {detailSignal.strategyName}
            </Descriptions.Item>
            <Descriptions.Item label={t('text.strategy.signalType')}>
              <Tag color={signalTypeColor[detailSignal.signalType]}>
                {signalTypeLabel[detailSignal.signalType]}
              </Tag>
            </Descriptions.Item>
            <Descriptions.Item label={t('text.strategy.exchange')}>
              {detailSignal.exchange?.toUpperCase()}
            </Descriptions.Item>
            <Descriptions.Item label={t('text.strategy.symbol')}>
              {detailSignal.symbol}
            </Descriptions.Item>
            <Descriptions.Item label={t('text.strategy.interval')}>
              {KLINE_INTERVAL_LABELS[detailSignal.interval] || detailSignal.interval}
            </Descriptions.Item>
            <Descriptions.Item label={t('text.strategy.signalStrength')}>
              <Progress
                percent={detailSignal.signalStrength}
                size="small"
                style={{ width: 120 }}
              />
            </Descriptions.Item>
            <Descriptions.Item label={t('text.strategy.price')}>
              {detailSignal.price}
            </Descriptions.Item>
            <Descriptions.Item label={t('text.strategy.signalTime')}>
              {dayjs(detailSignal.signalTime).format('YYYY-MM-DD HH:mm:ss')}
            </Descriptions.Item>
            <Descriptions.Item label={t('text.strategy.indicatorValues')} span={2}>
              {detailSignal.indicators && (
                <Space direction="vertical" size="small">
                  {Object.entries(detailSignal.indicators).map(([key, value]) => (
                    <Tag key={key} color="processing">
                      {key}: {typeof value === 'number' ? value.toFixed(4) : value}
                    </Tag>
                  ))}
                </Space>
              )}
            </Descriptions.Item>
            <Descriptions.Item label={t('text.strategy.description')} span={2}>
              {detailSignal.description}
            </Descriptions.Item>
          </Descriptions>
        )}
      </Modal>
    </div>
  );
};
