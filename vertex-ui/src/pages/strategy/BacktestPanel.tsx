import { useState, useRef, useEffect, useCallback } from 'react';
import {
  Modal, Form, DatePicker, InputNumber, Button, Table, Statistic,
  Row, Col, Card, Tag, Spin, message, Space, Divider, Empty,
} from 'antd';
import {
  ArrowUpOutlined,
  ArrowDownOutlined,
  ExperimentOutlined,
} from '@ant-design/icons';
import dayjs from 'dayjs';
import { useTranslation } from 'react-i18next';
import { backtestApi, BacktestResultVO, BacktestConfigDTO } from '../../api/strategy';

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
      };

      const response = await backtestApi.run(config);
      if (response.code === 200) {
        setResult(response.data);
        message.success(t('message.strategy.backtestSuccess'));
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
          <DatePicker.RangePicker showTime style={{ width: 340 }} />
        </Form.Item>
        <Space.Compact style={{ marginLeft: 8 }}>
          <Button onClick={() => form.setFieldValue('timeRange', [dayjs().subtract(7, 'day'), dayjs()])}>
            {t('text.strategy.backtestPreset7d')}
          </Button>
          <Button onClick={() => form.setFieldValue('timeRange', [dayjs().subtract(30, 'day'), dayjs()])}>
            {t('text.strategy.backtestPreset30d')}
          </Button>
          <Button onClick={() => form.setFieldValue('timeRange', [dayjs().subtract(90, 'day'), dayjs()])}>
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
        <Form.Item>
          <Button type="primary" onClick={handleRun} loading={loading} icon={<ExperimentOutlined />}>
            {t('text.strategy.runBacktest')}
          </Button>
        </Form.Item>
      </Form>

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
