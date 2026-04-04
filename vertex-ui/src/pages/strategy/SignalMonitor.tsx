import { useState, useEffect, useRef } from 'react';
import {
  Table, Button, Space, message, Tag, Select, DatePicker, Modal, Descriptions,
  Progress, Badge, Tooltip, Dropdown, Card, Row, Col, Statistic, Empty,
} from 'antd';
import type { Dayjs } from 'dayjs';
import { formatTimestamp } from '../../utils/date';
import {
  ReloadOutlined,
  ThunderboltOutlined,
  EyeOutlined,
  LinkOutlined,
  DisconnectOutlined,
  ExperimentOutlined,
  CaretRightOutlined,
  DownOutlined,
  ArrowUpOutlined,
  ArrowDownOutlined,
  CloseOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import {
  signalApi,
  strategyApi,
  backtestApi,
  SignalVO,
  SignalType,
  StrategyVO,
  BacktestResultVO,
} from '../../api/strategy';
import { KLINE_INTERVAL_LABELS, KLineInterval } from '../../api/quote';
import { symbolApi, ExchangeSymbolVO } from '../../api/symbol';
import { useNotification } from '../../hooks/useNotification';

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

/** 快速回测结果面板（内嵌在信号监控页面中） */
const QuickBacktestResult = ({
  result,
  strategyName,
  days,
  onClose,
  t,
}: {
  result: BacktestResultVO;
  strategyName: string;
  days: number;
  onClose: () => void;
  t: (key: string) => string;
}) => {
  const tradeColumns = [
    {
      title: t('text.strategy.entryTime'),
      dataIndex: 'entryTime',
      key: 'entryTime',
      width: 150,
      render: (v: number | string) => formatTimestamp(v, 'YYYY-MM-DD HH:mm'),
    },
    {
      title: t('text.strategy.exitTime'),
      dataIndex: 'exitTime',
      key: 'exitTime',
      width: 150,
      render: (v: number | string) => formatTimestamp(v, 'YYYY-MM-DD HH:mm'),
    },
    {
      title: t('text.strategy.signalType'),
      key: 'direction',
      width: 70,
      render: (_: unknown, record: { entryPrice: string; exitPrice: string }) => {
        const entry = parseFloat(record.entryPrice);
        const exit = parseFloat(record.exitPrice);
        return <Tag color={exit >= entry ? 'success' : 'error'}>{exit >= entry ? 'LONG' : 'LONG'}</Tag>;
      },
    },
    {
      title: t('text.strategy.entryPrice'),
      dataIndex: 'entryPrice',
      key: 'entryPrice',
      width: 130,
      render: (v: string) => (v != null && v !== '') ? Number(v).toFixed(6) : '-',
    },
    {
      title: t('text.strategy.exitPrice'),
      dataIndex: 'exitPrice',
      key: 'exitPrice',
      width: 130,
      render: (v: string) => (v != null && v !== '') ? Number(v).toFixed(6) : '-',
    },
    {
      title: t('text.strategy.quantity'),
      dataIndex: 'quantity',
      key: 'quantity',
      width: 120,
      render: (v: string) => parseFloat(v).toFixed(6),
    },
    {
      title: t('text.strategy.profit'),
      dataIndex: 'profit',
      key: 'profit',
      width: 110,
      render: (v: string) => {
        const num = parseFloat(v);
        return (
          <span style={{ color: num >= 0 ? '#52c41a' : '#ff4d4f', fontWeight: 600 }}>
            {num >= 0 ? '+' : ''}{parseFloat(v).toFixed(2)}
          </span>
        );
      },
    },
    {
      title: t('text.strategy.profitPercent'),
      dataIndex: 'profitPercent',
      key: 'profitPercent',
      width: 100,
      render: (v: string) => {
        const num = parseFloat(v);
        return (
          <Tag color={num >= 0 ? 'success' : 'error'}>
            {num >= 0 ? '+' : ''}{parseFloat(v).toFixed(2)}%
          </Tag>
        );
      },
    },
  ];

  const returnNum = parseFloat(result.returnRate);

  return (
    <Card
      size="small"
      style={{ marginBottom: 16, borderColor: '#1890ff', borderWidth: 1 }}
      title={
        <Space wrap>
          <ExperimentOutlined style={{ color: '#1890ff' }} />
          <span>{t('text.strategy.quickBacktestResult')}</span>
          <Tag color="blue">{strategyName}</Tag>
          <Tag>{t('text.strategy.recentDays').replace('{{days}}', String(days))}</Tag>
          {result.startTime != null && result.endTime != null && (
            <span style={{ fontSize: 12, color: '#999' }}>
              {formatTimestamp(result.startTime, 'YYYY-MM-DD HH:mm')} ~ {formatTimestamp(result.endTime, 'YYYY-MM-DD HH:mm')}
            </span>
          )}
        </Space>
      }
      extra={
        <Button type="text" icon={<CloseOutlined />} onClick={onClose} size="small" />
      }
    >
      {/* 统计摘要 */}
      <Row gutter={[12, 12]} style={{ marginBottom: 16 }}>
        <Col span={4}>
          <Statistic
            title={t('text.strategy.returnRate')}
            value={result.returnRate}
            suffix="%"
            precision={2}
            valueStyle={{ color: returnNum >= 0 ? '#52c41a' : '#ff4d4f', fontSize: 20 }}
            prefix={returnNum >= 0 ? <ArrowUpOutlined /> : <ArrowDownOutlined />}
          />
        </Col>
        <Col span={3}>
          <Statistic
            title={t('text.strategy.totalProfit')}
            value={result.totalProfit}
            precision={2}
            valueStyle={{ color: parseFloat(result.totalProfit) >= 0 ? '#52c41a' : '#ff4d4f', fontSize: 20 }}
            prefix={parseFloat(result.totalProfit) >= 0 ? '+' : ''}
          />
        </Col>
        <Col span={3}>
          <Statistic title={t('text.strategy.totalTrades')} value={result.totalTrades} valueStyle={{ fontSize: 20 }}
            suffix={<span style={{ fontSize: 12, color: '#999' }}>({result.winningTrades}W/{result.losingTrades}L)</span>}
          />
        </Col>
        <Col span={3}>
          <Statistic title={t('text.strategy.winRate')} value={result.winRate} suffix="%" precision={2} valueStyle={{ fontSize: 20 }} />
        </Col>
        <Col span={3}>
          <Statistic title={t('text.strategy.profitLossRatio')} value={result.profitLossRatio} precision={2} valueStyle={{ fontSize: 20 }} />
        </Col>
        <Col span={3}>
          <Statistic title={t('text.strategy.maxDrawdown')} value={result.maxDrawdown} suffix="%" precision={2} valueStyle={{ color: '#ff4d4f', fontSize: 20 }} />
        </Col>
        <Col span={3}>
          <Statistic title={t('text.strategy.sharpeRatio')} value={result.sharpeRatio} precision={2} valueStyle={{ fontSize: 20 }} />
        </Col>
        <Col span={2}>
          <Statistic
            title={t('text.strategy.finalCapital')}
            value={result.finalCapital}
            precision={2}
            valueStyle={{ fontSize: 14, color: '#666' }}
          />
        </Col>
      </Row>

      {/* 完整交易记录 */}
      {result.trades && result.trades.length > 0 ? (
        <Table
          dataSource={result.trades}
          columns={tradeColumns}
          rowKey={(_, i) => String(i)}
          size="small"
          pagination={result.trades.length > 10 ? { pageSize: 10, size: 'small', showTotal: (total: number) => `共 ${total} 笔交易` } : false}
          scroll={{ x: 960 }}
        />
      ) : (
        <Empty description={t('text.strategy.noTradesInPeriod')} />
      )}
    </Card>
  );
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
  const [symbolOptions, setSymbolOptions] = useState<{ label: string; value: string }[]>([]);
  const [symbolsLoading, setSymbolsLoading] = useState(false);
  const [interval, setInterval] = useState<KLineInterval | undefined>();
  const [signalType, setSignalType] = useState<SignalType | undefined>();
  const [timeRange, setTimeRange] = useState<[Dayjs, Dayjs] | null>(null);
  const [strategyId, setStrategyId] = useState<string | undefined>();

  // 策略列表（用于手动分析按钮）
  const [strategies, setStrategies] = useState<StrategyVO[]>([]);

  // 信号详情弹窗
  const [detailVisible, setDetailVisible] = useState(false);
  const [detailSignal, setDetailSignal] = useState<SignalVO | null>(null);

  // 快速回测状态
  const [quickBacktestResult, setQuickBacktestResult] = useState<BacktestResultVO | null>(null);
  const [quickBacktestStrategy, setQuickBacktestStrategy] = useState<string>('');
  const [quickBacktestDays, setQuickBacktestDays] = useState<number>(30);
  const [quickBacktestLoading, setQuickBacktestLoading] = useState<string | null>(null); // strategyId being tested

  // 从全局通知上下文获取 WebSocket 连接状态和实时信号
  const { connected, notifications: globalNotifications } = useNotification();

  // 监听全局通知中的最新信号，自动插入表格（仅第 1 页）
  const lastNotificationIdRef = useRef<string | null>(null);

  useEffect(() => {
    if (globalNotifications.length > 0) {
      const latest = globalNotifications[0];
      if (latest.id !== lastNotificationIdRef.current) {
        lastNotificationIdRef.current = latest.id;
        if (pageNum === 1) {
          setSignals((prev) => {
            const newList = [latest.signal, ...prev];
            return newList.slice(0, pageSize);
          });
          setTotal((prev) => prev + 1);
        }
      }
    }
  }, [globalNotifications, pageNum, pageSize]);

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

  const fetchSymbolsByExchange = async (exch: string) => {
    if (!exch) { setSymbolOptions([]); return; }
    setSymbolsLoading(true);
    try {
      const res = await symbolApi.list(exch);
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

  /** 单次分析（原逻辑） */
  const handleAnalyze = async (sid: string) => {
    try {
      await signalApi.analyze(sid);
      message.success(t('message.strategy.analyzeSubmitted'));
      setTimeout(loadData, 1000);
    } catch {
      message.error(t('message.strategy.loadFailed'));
    }
  };

  /** 快速回测（参数与策略配置回测默认一致，便于结果对比） */
  const handleQuickBacktest = async (sid: string, sName: string, days: number) => {
    setQuickBacktestLoading(sid);
    try {
      const response = await backtestApi.quick({ strategyId: sid, days, initialCapital: 10000, positionRatio: 1, feeRate: 0.001 });
      if (response.code === 200) {
        setQuickBacktestResult(response.data);
        setQuickBacktestStrategy(sName);
        setQuickBacktestDays(days);
        message.success(t('message.strategy.backtestSuccess'));
      } else {
        message.error(response.message || t('message.strategy.backtestFailed'));
      }
    } catch {
      message.error(t('message.strategy.backtestFailed'));
    } finally {
      setQuickBacktestLoading(null);
    }
  };

  const showDetail = (record: SignalVO) => {
    setDetailSignal(record);
    setDetailVisible(true);
  };

  /** 构建每个策略的下拉菜单 */
  const buildStrategyMenuItems = (s: StrategyVO) => [
    {
      key: 'analyze',
      icon: <CaretRightOutlined />,
      label: t('text.strategy.singleAnalysis'),
      onClick: () => handleAnalyze(s.id),
    },
    { type: 'divider' as const },
    {
      key: 'quick-7',
      icon: <ExperimentOutlined />,
      label: t('text.strategy.quickBacktestDays').replace('{{days}}', '7'),
      onClick: () => handleQuickBacktest(s.id, s.name, 7),
    },
    {
      key: 'quick-30',
      icon: <ExperimentOutlined />,
      label: t('text.strategy.quickBacktestDays').replace('{{days}}', '30'),
      onClick: () => handleQuickBacktest(s.id, s.name, 30),
    },
    {
      key: 'quick-90',
      icon: <ExperimentOutlined />,
      label: t('text.strategy.quickBacktestDays').replace('{{days}}', '90'),
      onClick: () => handleQuickBacktest(s.id, s.name, 90),
    },
  ];

  const columns = [
    {
      title: t('text.strategy.signalTime'),
      dataIndex: 'signalTime',
      key: 'signalTime',
      width: 180,
      render: (val: number | string) => formatTimestamp(val),
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

  const enabledStrategies = strategies.filter((s) => s.enabled === 1);

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Space>
          <h2 style={{ margin: 0, fontSize: 20, fontWeight: 600 }}>{t('text.strategy.signalTitle')}</h2>
          <Tooltip title={connected ? t('text.strategy.connected') : t('text.strategy.disconnected')}>
            <Badge
              status={connected ? 'success' : 'error'}
              text={
                <span style={{ fontSize: 12, color: connected ? '#52c41a' : '#ff4d4f' }}>
                  {connected ? <LinkOutlined /> : <DisconnectOutlined />}
                  {' '}
                  {connected ? 'Live' : 'Offline'}
                </span>
              }
            />
          </Tooltip>
        </Space>
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
          onChange={(val: string | undefined) => {
            setExchange(val);
            setSymbol(undefined);
            if (val) fetchSymbolsByExchange(val);
            else setSymbolOptions([]);
          }}
        >
          <Select.Option value="binance">Binance</Select.Option>
          <Select.Option value="okx">OKX</Select.Option>
        </Select>

        <Select
          allowClear
          showSearch
          placeholder={t('placeholder.strategy.symbol')}
          style={{ width: 180 }}
          value={symbol}
          loading={symbolsLoading}
          options={symbolOptions}
          onChange={(val: string | undefined) => setSymbol(val)}
          filterOption={(input, option) =>
            (option?.value as string ?? '').toLowerCase().includes(input.toLowerCase())
          }
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

      {/* 策略运行按钮组 - 下拉菜单支持单次分析 + 快速回测 */}
      {enabledStrategies.length > 0 && (
        <Space wrap style={{ marginBottom: 16 }}>
          <span style={{ fontSize: 12, color: '#999' }}>{t('text.strategy.runStrategy')}:</span>
          {enabledStrategies.map((s) => (
            <Dropdown
              key={s.id}
              menu={{ items: buildStrategyMenuItems(s) }}
              trigger={['click']}
            >
              <Button
                size="small"
                icon={<ThunderboltOutlined />}
                loading={quickBacktestLoading === s.id}
              >
                {s.name} <DownOutlined style={{ fontSize: 10, marginLeft: 4 }} />
              </Button>
            </Dropdown>
          ))}
        </Space>
      )}

      {/* 快速回测结果面板 */}
      {quickBacktestResult && (
        <QuickBacktestResult
          result={quickBacktestResult}
          strategyName={quickBacktestStrategy}
          days={quickBacktestDays}
          onClose={() => setQuickBacktestResult(null)}
          t={t}
        />
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
              {formatTimestamp(detailSignal.signalTime)}
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
