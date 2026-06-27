import { useState, useRef, useEffect, useCallback } from 'react';
import {
  Modal, Form, DatePicker, InputNumber, Button, Table, Statistic,
  Row, Col, Card, Tag, Spin, message, Space, Divider, Empty,
  Checkbox, Alert, Progress, Popconfirm, Tooltip, Popover,
} from 'antd';
import {
  ArrowUpOutlined,
  ArrowDownOutlined,
  ExperimentOutlined,
  RobotOutlined,
  DeleteOutlined,
} from '@ant-design/icons';
import dayjs from 'dayjs';
import { useTranslation } from 'react-i18next';
import { backtestApi, BacktestResultVO, BacktestConfigDTO } from '../../api/strategy';
import { aiApi, AiBacktestProgress } from '../../api/ai';

interface BacktestPanelProps {
  strategyId: string;
  strategyName: string;
  visible: boolean;
  onClose: () => void;
}

/** 简易资金曲线图（纯Canvas绘制，不依赖第三方图表库） */
const EquityChart = ({ data }: { data: { time: number; equity: number }[] }) => {
  const canvasRef = useRef<HTMLCanvasElement>(null);

  const draw = useCallback(() => {
    const canvas = canvasRef.current;
    if (!canvas || data.length < 2) return;

    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    const dpr = window.devicePixelRatio || 1;
    const rect = canvas.getBoundingClientRect();
    canvas.width = rect.width * dpr;
    canvas.height = rect.height * dpr;
    ctx.scale(dpr, dpr);

    const width = rect.width;
    const height = rect.height;
    const padding = { top: 20, right: 20, bottom: 30, left: 60 };
    const chartW = width - padding.left - padding.right;
    const chartH = height - padding.top - padding.bottom;

    // 清空
    ctx.clearRect(0, 0, width, height);

    const equities = data.map((d) => Number(d.equity));
    const minE = Math.min(...equities) * 0.995;
    const maxE = Math.max(...equities) * 1.005;
    const rangeE = maxE - minE || 1;

    // 背景网格
    ctx.strokeStyle = '#f0f0f0';
    ctx.lineWidth = 1;
    for (let i = 0; i <= 4; i++) {
      const y = padding.top + (chartH / 4) * i;
      ctx.beginPath();
      ctx.moveTo(padding.left, y);
      ctx.lineTo(padding.left + chartW, y);
      ctx.stroke();

      // Y 轴标签
      const val = maxE - (rangeE / 4) * i;
      ctx.fillStyle = '#999';
      ctx.font = '11px sans-serif';
      ctx.textAlign = 'right';
      ctx.fillText(val.toFixed(0), padding.left - 6, y + 4);
    }

    // 绘制曲线
    const firstEquity = Number(data[0].equity);
    ctx.beginPath();
    ctx.strokeStyle = '#1890ff';
    ctx.lineWidth = 2;

    for (let i = 0; i < data.length; i++) {
      const x = padding.left + (i / (data.length - 1)) * chartW;
      const y = padding.top + ((maxE - equities[i]) / rangeE) * chartH;
      if (i === 0) ctx.moveTo(x, y);
      else ctx.lineTo(x, y);
    }
    ctx.stroke();

    // 填充区域
    const lastX = padding.left + chartW;
    const baseY = padding.top + ((maxE - firstEquity) / rangeE) * chartH;
    ctx.lineTo(lastX, padding.top + chartH);
    ctx.lineTo(padding.left, padding.top + chartH);
    ctx.closePath();
    ctx.fillStyle = 'rgba(24, 144, 255, 0.08)';
    ctx.fill();

    // X轴时间标签
    ctx.fillStyle = '#999';
    ctx.font = '10px sans-serif';
    ctx.textAlign = 'center';
    const step = Math.max(1, Math.floor(data.length / 5));
    for (let i = 0; i < data.length; i += step) {
      const x = padding.left + (i / (data.length - 1)) * chartW;
      const label = dayjs(Number(data[i].time)).format('MM-DD');
      ctx.fillText(label, x, height - 8);
    }

    // 基准线（初始资金）
    ctx.setLineDash([4, 4]);
    ctx.strokeStyle = '#faad14';
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.moveTo(padding.left, baseY);
    ctx.lineTo(padding.left + chartW, baseY);
    ctx.stroke();
    ctx.setLineDash([]);
  }, [data]);

  useEffect(() => {
    draw();
    window.addEventListener('resize', draw);
    return () => window.removeEventListener('resize', draw);
  }, [draw]);

  if (data.length < 2) {
    return <Empty description="数据不足" />;
  }

  return (
    <canvas
      ref={canvasRef}
      style={{ width: '100%', height: 250, display: 'block' }}
    />
  );
};

/**
 * 缓存命中 + AI 进度提示条。
 * - 当结果来自缓存时显示 ⚡ 命中提示 + 清除按钮
 * - 当 AI 分析进行中时轮询进度
 */
const BacktestCacheBar = ({
  result,
  onCleared,
}: {
  result: BacktestResultVO;
  onCleared: () => void;
}) => {
  const { t } = useTranslation();
  const [progress, setProgress] = useState<AiBacktestProgress | null>(null);

  // 仅当 AI 状态为 PENDING/RUNNING 时轮询；否则一次性查询
  useEffect(() => {
    if (!result.cacheKey) return;
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;

    const fetchOnce = async () => {
      try {
        const res = await aiApi.getBacktestProgress(result.cacheKey!);
        if (cancelled) return;
        const prog = res.data || null;
        setProgress(prog);
        if (prog && (prog.status === 'PENDING' || prog.status === 'RUNNING')) {
          timer = setTimeout(fetchOnce, 3000);
        }
      } catch {
        // 静默
      }
    };
    fetchOnce();
    return () => {
      cancelled = true;
      if (timer) clearTimeout(timer);
    };
  }, [result.cacheKey]);

  const handleClear = async () => {
    if (!result.cacheKey) return;
    try {
      await aiApi.clearBacktestCache(result.cacheKey);
      message.success(t('text.strategy.cacheClear'));
      onCleared();
    } catch {
      message.error(t('text.strategy.aiFailed'));
    }
  };

  const handleRetriggerAi = async () => {
    if (!result.cacheKey) return;
    try {
      const res = await aiApi.retriggerBacktestAnalysis(result.cacheKey);
      if (res.data) {
        message.success(t('text.strategy.aiInProgress'));
        // 立即设置一个 PENDING 进度，触发 useEffect 轮询
        setProgress({
          cacheKey: result.cacheKey,
          strategyId: 0,
          strategyName: '',
          total: result.trades?.length || 0,
          completed: 0,
          failed: 0,
          status: 'PENDING',
          startedAt: Date.now(),
          updatedAt: Date.now(),
          completedAt: null,
          errorMessage: null,
        });
      } else {
        message.warning('已在分析中或不可触发');
      }
    } catch {
      message.error('提交失败（AI 未启用？）');
    }
  };

  const cachedAt = result.cachedAt ? dayjs(result.cachedAt).format('YYYY-MM-DD HH:mm:ss') : '-';
  const shortKey = result.cacheKey ? result.cacheKey.substring(0, 12) + '…' : '-';

  let aiStatus: React.ReactNode = null;
  if (progress) {
    if (progress.status === 'COMPLETED') {
      aiStatus = (
        <Tag color="green" icon={<RobotOutlined />}>
          {t('text.strategy.aiCompleted')} {progress.completed}/{progress.total}
          {progress.failed > 0 ? ` (${progress.failed})` : ''}
        </Tag>
      );
    } else if (progress.status === 'RUNNING' || progress.status === 'PENDING') {
      const percent = progress.total > 0 ? Math.round((progress.completed / progress.total) * 100) : 0;
      aiStatus = (
        <Space size={6}>
          <RobotOutlined style={{ color: '#1677ff' }} />
          <span>
            {t('text.strategy.aiInProgress')} {progress.completed}/{progress.total}
          </span>
          <Progress percent={percent} size="small" style={{ width: 100 }} />
        </Space>
      );
    } else if (progress.status === 'FAILED') {
      aiStatus = <Tag color="red">{t('text.strategy.aiFailed')}</Tag>;
    }
  }

  return (
    <Alert
      type={result.cached ? 'info' : 'success'}
      showIcon
      style={{ marginBottom: 16 }}
      message={
        <Space size={12} wrap>
          {result.cached ? (
            <span>
              ⚡ {t('text.strategy.cacheHit')} · {t('text.strategy.cacheCreatedAt')}{' '}
              <code>{cachedAt}</code>
            </span>
          ) : (
            <span>
              {t('text.strategy.cacheNew')}（{cachedAt}）
            </span>
          )}
          <Tooltip title={result.cacheKey || ''}>
            <Tag>{t('text.strategy.cacheKey')}: {shortKey}</Tag>
          </Tooltip>
          {aiStatus}
          {(!progress || progress.status === 'COMPLETED' || progress.status === 'FAILED') && (
            <Button
              size="small"
              icon={<RobotOutlined />}
              type="link"
              onClick={handleRetriggerAi}
            >
              {t('text.strategy.aiReAnalyze')}
            </Button>
          )}
          <Popconfirm
            title={t('text.strategy.cacheClearConfirm')}
            onConfirm={handleClear}
          >
            <Button size="small" icon={<DeleteOutlined />} type="link">
              {t('text.strategy.cacheClear')}
            </Button>
          </Popconfirm>
        </Space>
      }
    />
  );
};

/**
 * 单笔 trade 的 AI 解读 Popover。
 * 点击 🤖 按钮，请求 AiTradeAnalysis 并弹出 quality / verdict / factors / summary。
 */
const TradeAiPopover = ({ cacheKey, tradeIndex }: { cacheKey: string; tradeIndex: number }) => {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState<import('../../api/ai').AiTradeAnalysis | null>(null);

  const fetchOnOpen = async (newOpen: boolean) => {
    setOpen(newOpen);
    if (!newOpen || data) return;
    setLoading(true);
    try {
      const res = await aiApi.getBacktestTrade(cacheKey, tradeIndex);
      setData(res.data || null);
    } catch {
      // 静默
    } finally {
      setLoading(false);
    }
  };

  const content = (() => {
    if (loading) return <Spin size="small" />;
    if (!data) return <span style={{ color: '#999' }}>{t('text.strategy.tradeAiNoData')}</span>;
    if (data.errorMessage) {
      return <span style={{ color: '#ff4d4f' }}>{t('text.strategy.aiFailed')}: {data.errorMessage}</span>;
    }
    const verdictColor: Record<string, string> = {
      GOOD_ENTRY: 'green', LATE_ENTRY: 'orange', FALSE_SIGNAL: 'red',
      GOOD_EXIT: 'green', EARLY_EXIT: 'orange', BAD_STOP_LOSS: 'red',
      LUCKY_PROFIT: 'gold',
    };
    return (
      <div style={{ maxWidth: 360 }}>
        <Space size={6} style={{ marginBottom: 6 }}>
          <Tag color={data.verdict ? verdictColor[data.verdict] : 'default'}>
            {data.verdict
              ? t(`text.strategy.aiVerdict.${data.verdict}`, { defaultValue: data.verdict })
              : '-'}
          </Tag>
          <span>{t('text.strategy.aiConfidence')}:</span>
          <Progress percent={Math.round((data.quality || 0) * 100)} size="small" style={{ width: 80 }} />
        </Space>
        {data.summary && (
          <div style={{ marginBottom: 6, padding: '4px 8px', background: '#fafafa', borderRadius: 4 }}>
            {data.summary}
          </div>
        )}
        {data.entryFactors && data.entryFactors.length > 0 && (
          <div style={{ marginBottom: 4 }}>
            <strong>{t('text.strategy.tradeAiEntry')}:</strong>
            <ul style={{ margin: '2px 0 0 18px', paddingLeft: 0 }}>
              {data.entryFactors.map((f, i) => <li key={i}>{f}</li>)}
            </ul>
          </div>
        )}
        {data.exitFactors && data.exitFactors.length > 0 && (
          <div style={{ marginBottom: 4 }}>
            <strong>{t('text.strategy.tradeAiExit')}:</strong>
            <ul style={{ margin: '2px 0 0 18px', paddingLeft: 0 }}>
              {data.exitFactors.map((f, i) => <li key={i}>{f}</li>)}
            </ul>
          </div>
        )}
        {data.improvements && data.improvements.length > 0 && (
          <div>
            <strong style={{ color: '#1677ff' }}>{t('text.strategy.tradeAiSuggestions')}:</strong>
            <ul style={{ margin: '2px 0 0 18px', paddingLeft: 0, color: '#1677ff' }}>
              {data.improvements.map((f, i) => <li key={i}>{f}</li>)}
            </ul>
          </div>
        )}
      </div>
    );
  })();

  return (
    <Popover
      content={content}
      title={
        <>
          <RobotOutlined /> Trade #{tradeIndex + 1} · {t('text.strategy.aiSummary')}
        </>
      }
      trigger="click"
      open={open}
      onOpenChange={fetchOnOpen}
      placement="leftTop"
    >
      <Button size="small" icon={<RobotOutlined />} type="text" />
    </Popover>
  );
};

export const BacktestPanel = ({ strategyId, strategyName, visible, onClose }: BacktestPanelProps) => {
  const { t } = useTranslation();
  const [form] = Form.useForm();
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<BacktestResultVO | null>(null);

  const handleRun = async () => {
    try {
      const values = await form.validateFields();
      setLoading(true);
      setResult(null);

      const config: BacktestConfigDTO = {
        strategyId,
        startTime: values.timeRange[0].valueOf(),
        endTime: values.timeRange[1].valueOf(),
        initialCapital: values.initialCapital,
        positionRatio: values.positionRatio,
        feeRate: values.feeRate,
        enableAiAnalysis: !!values.enableAiAnalysis,
        forceRefresh: !!values.forceRefresh,
      };

      const response = await backtestApi.run(config);
      if (response.code === 200) {
        setResult(response.data);
        if (response.data?.cached) {
          message.success(t('text.strategy.cacheHit'));
        } else {
          message.success(t('message.strategy.backtestSuccess'));
        }
      } else {
        message.error(response.message || t('message.strategy.backtestFailed'));
      }
    } catch {
      message.error(t('message.strategy.backtestFailed'));
    } finally {
      setLoading(false);
    }
  };

  const tradeColumns = [
    {
      title: t('text.strategy.entryTime'),
      dataIndex: 'entryTime',
      key: 'entryTime',
      width: 160,
      render: (v: number | string) => dayjs(Number(v)).format('YYYY-MM-DD HH:mm'),
    },
    {
      title: t('text.strategy.exitTime'),
      dataIndex: 'exitTime',
      key: 'exitTime',
      width: 160,
      render: (v: number | string) => dayjs(Number(v)).format('YYYY-MM-DD HH:mm'),
    },
    {
      title: t('text.strategy.direction'),
      dataIndex: 'type',
      key: 'type',
      width: 70,
      render: (v: string) => (
        <Tag color={v === 'LONG' ? 'success' : 'blue'}>{v ?? '-'}</Tag>
      ),
    },
    {
      title: t('text.strategy.entryPrice'),
      dataIndex: 'entryPrice',
      key: 'entryPrice',
      width: 120,
      render: (v: string) => (v != null && v !== '') ? Number(v).toFixed(6) : '-',
    },
    {
      title: t('text.strategy.exitPrice'),
      dataIndex: 'exitPrice',
      key: 'exitPrice',
      width: 120,
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
      width: 100,
      render: (v: string) => {
        const num = parseFloat(v);
        return (
          <span style={{ color: num >= 0 ? '#52c41a' : '#ff4d4f', fontWeight: 600 }}>
            {num >= 0 ? '+' : ''}{v}
          </span>
        );
      },
    },
    {
      title: t('text.strategy.profitPercent'),
      dataIndex: 'profitPercent',
      key: 'profitPercent',
      width: 90,
      render: (v: string) => {
        const num = parseFloat(v);
        return (
          <Tag color={num >= 0 ? 'success' : 'error'}>
            {num >= 0 ? '+' : ''}{v}%
          </Tag>
        );
      },
    },
    {
      title: t('text.strategy.exitReason'),
      dataIndex: 'exitReason',
      key: 'exitReason',
      width: 90,
      render: (v: string) => {
        const colorMap: Record<string, string> = {
          SIGNAL:           'blue',
          STOP_LOSS:        'error',
          TAKE_PROFIT:      'success',
          END_OF_BACKTEST:  'default',
        };
        const labelKey: Record<string, string> = {
          SIGNAL:           'text.strategy.exitReasonSignal',
          STOP_LOSS:        'text.strategy.exitReasonStopLoss',
          TAKE_PROFIT:      'text.strategy.exitReasonTakeProfit',
          END_OF_BACKTEST:  'text.strategy.exitReasonEnd',
        };
        return v
          ? <Tag color={colorMap[v] ?? 'default'}>{t(labelKey[v] ?? v)}</Tag>
          : '-';
      },
    },
    {
      title: 'AI',
      key: 'aiAnalysis',
      width: 60,
      render: (_: unknown, __: unknown, index: number) => (
        result?.cacheKey
          ? <TradeAiPopover cacheKey={result.cacheKey} tradeIndex={index} />
          : <span style={{ color: '#ccc' }}>-</span>
      ),
    },
  ];

  return (
    <Modal
      title={
        <Space>
          <ExperimentOutlined />
          {t('text.strategy.backtestTitle')} - {strategyName}
        </Space>
      }
      open={visible}
      onCancel={onClose}
      width={1100}
      footer={null}
      destroyOnClose
    >
      {/* 配置表单 */}
      <Form form={form} layout="inline" style={{ marginBottom: 20 }}>
        <Form.Item
          name="timeRange"
          label={t('text.strategy.timeRange')}
          rules={[{ required: true, message: t('placeholder.common.select') }]}
        >
          <DatePicker.RangePicker
            showTime={{
              defaultValue: [
                dayjs('00:00:00', 'HH:mm:ss'),
                dayjs('23:59:59', 'HH:mm:ss'),
              ],
            }}
            style={{ width: 340 }}
          />
        </Form.Item>
        <Space.Compact style={{ marginLeft: 8 }}>
          <Button onClick={() => form.setFieldValue('timeRange', [dayjs().subtract(7, 'day').startOf('day'), dayjs().endOf('day')])}>
            {t('text.strategy.backtestPreset7d')}
          </Button>
          <Button onClick={() => form.setFieldValue('timeRange', [dayjs().subtract(30, 'day').startOf('day'), dayjs().endOf('day')])}>
            {t('text.strategy.backtestPreset30d')}
          </Button>
          <Button onClick={() => form.setFieldValue('timeRange', [dayjs().subtract(90, 'day').startOf('day'), dayjs().endOf('day')])}>
            {t('text.strategy.backtestPreset90d')}
          </Button>
        </Space.Compact>
        <Form.Item name="initialCapital" label={t('text.strategy.initialCapital')} initialValue={10000}>
          <InputNumber min={100} step={1000} style={{ width: 110 }} addonAfter="U" />
        </Form.Item>
        <Form.Item name="positionRatio" label={t('text.strategy.positionRatio')} initialValue={1}>
          <InputNumber min={0.1} max={1} step={0.1} style={{ width: 80 }} />
        </Form.Item>
        <Form.Item name="feeRate" label={t('text.strategy.feeRate')} initialValue={0.001}>
          <InputNumber min={0} max={0.01} step={0.0001} style={{ width: 90 }} />
        </Form.Item>
        <Form.Item name="enableAiAnalysis" valuePropName="checked" initialValue={false}>
          <Tooltip title={t('text.strategy.enableAiAnalysisTip')}>
            <Checkbox>{t('text.strategy.enableAiAnalysis')}</Checkbox>
          </Tooltip>
        </Form.Item>
        <Form.Item name="forceRefresh" valuePropName="checked" initialValue={false}>
          <Tooltip title={t('text.strategy.forceRefreshTip')}>
            <Checkbox>{t('text.strategy.forceRefresh')}</Checkbox>
          </Tooltip>
        </Form.Item>
        <Form.Item>
          <Button type="primary" onClick={handleRun} loading={loading} icon={<ExperimentOutlined />}>
            {t('text.strategy.runBacktest')}
          </Button>
        </Form.Item>
      </Form>

      {/* 缓存命中 + AI 进度提示条 */}
      {result?.cacheKey && (
        <BacktestCacheBar result={result} onCleared={() => setResult(null)} />
      )}

      <Spin spinning={loading}>
        {result && (
          <>
            {/* 统计卡片 */}
            <Row gutter={[16, 16]} style={{ marginBottom: 20 }}>
              <Col span={4}>
                <Card size="small">
                  <Statistic
                    title={t('text.strategy.returnRate')}
                    value={result.returnRate}
                    suffix="%"
                    precision={2}
                    valueStyle={{ color: parseFloat(result.returnRate) >= 0 ? '#52c41a' : '#ff4d4f' }}
                    prefix={parseFloat(result.returnRate) >= 0 ? <ArrowUpOutlined /> : <ArrowDownOutlined />}
                  />
                </Card>
              </Col>
              <Col span={4}>
                <Card size="small">
                  <Statistic title={t('text.strategy.winRate')} value={result.winRate} suffix="%" precision={2} />
                </Card>
              </Col>
              <Col span={4}>
                <Card size="small">
                  <Statistic title={t('text.strategy.profitLossRatio')} value={result.profitLossRatio} precision={2} />
                </Card>
              </Col>
              <Col span={4}>
                <Card size="small">
                  <Statistic
                    title={t('text.strategy.maxDrawdown')}
                    value={result.maxDrawdown}
                    suffix="%"
                    precision={2}
                    valueStyle={{ color: '#ff4d4f' }}
                  />
                </Card>
              </Col>
              <Col span={4}>
                <Card size="small">
                  <Statistic title={t('text.strategy.sharpeRatio')} value={result.sharpeRatio} precision={2} />
                </Card>
              </Col>
              <Col span={4}>
                <Card size="small">
                  <Statistic
                    title={t('text.strategy.totalTrades')}
                    value={result.totalTrades}
                    suffix={
                      <span style={{ fontSize: 12, color: '#999' }}>
                        ({result.winningTrades}W / {result.losingTrades}L)
                      </span>
                    }
                  />
                </Card>
              </Col>
            </Row>

            {/* 资金曲线 */}
            <Card
              title={t('text.strategy.equityCurve')}
              size="small"
              style={{ marginBottom: 20 }}
              extra={
                <Space size="small" style={{ fontSize: 12, color: '#999' }} wrap>
                  <span>{t('text.strategy.backtestTimeRange')}: {dayjs(Number(result.startTime)).format('YYYY-MM-DD HH:mm')} ~ {dayjs(Number(result.endTime)).format('YYYY-MM-DD HH:mm')}</span>
                  <Divider type="vertical" />
                  <span>{t('text.strategy.initialCapital')}: {result.initialCapital}</span>
                  <Divider type="vertical" />
                  <span>{t('text.strategy.finalCapital')}: {result.finalCapital}</span>
                  <Divider type="vertical" />
                  <span style={{ color: parseFloat(result.totalProfit) >= 0 ? '#52c41a' : '#ff4d4f', fontWeight: 600 }}>
                    {t('text.strategy.totalProfit')}: {parseFloat(result.totalProfit) >= 0 ? '+' : ''}{result.totalProfit}
                  </span>
                </Space>
              }
            >
              <EquityChart data={result.equityCurve} />
            </Card>

            {/* 交易记录 */}
            <Card title={t('text.strategy.tradeRecords')} size="small">
              <Table
                dataSource={result.trades}
                columns={tradeColumns}
                rowKey={(_, i) => String(i)}
                size="small"
                pagination={{ pageSize: 10, showTotal: (total) => `共 ${total} 条` }}
                scroll={{ x: 900 }}
              />
            </Card>
          </>
        )}

        {!result && !loading && (
          <div style={{ textAlign: 'center', padding: '60px 0', color: '#999' }}>
            <ExperimentOutlined style={{ fontSize: 48, marginBottom: 16, display: 'block' }} />
            <p>配置回测参数后点击"执行回测"开始</p>
            <p style={{ fontSize: 12, marginTop: 8 }}>{t('text.strategy.backtestTip')}</p>
          </div>
        )}
      </Spin>
    </Modal>
  );
};
