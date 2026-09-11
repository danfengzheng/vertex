import { useState } from 'react';
import { Card, Form, Button, DatePicker, InputNumber, Select, Switch, Row, Col, Statistic, Alert, Space, message, Descriptions } from 'antd';
import { ThunderboltOutlined } from '@ant-design/icons';
import dayjs, { Dayjs } from 'dayjs';
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer } from 'recharts';
import { mmGridApi, BacktestResult } from '../../api/mmGrid';

const { RangePicker } = DatePicker;

export const GridBacktestPanel = ({ strategyId }: { strategyId: string }) => {
  const [running, setRunning] = useState(false);
  const [result, setResult] = useState<BacktestResult | null>(null);
  const [range, setRange] = useState<[Dayjs, Dayjs]>([dayjs().subtract(30, 'day'), dayjs()]);
  const [interval, setInterval] = useState('5m');
  const [initialCapital, setInitialCapital] = useState(3000);
  const [simulateProtection, setSimulateProtection] = useState(true);

  const onRun = async () => {
    setRunning(true);
    setResult(null);
    try {
      const res = await mmGridApi.backtest(strategyId, {
        startTime: range[0].valueOf(),
        endTime: range[1].valueOf(),
        klineInterval: interval,
        initialCapital,
        simulateProtection,
      });
      if (res.code === 200 && res.data) {
        setResult(res.data);
        message.success('回测完成');
      } else {
        message.error(res.message || 'backtest failed');
      }
    } catch (e) {
      message.error(e instanceof Error ? e.message : 'backtest error');
    } finally {
      setRunning(false);
    }
  };

  const equity = result?.equityCurve ?? [];
  const chartData = equity.map(p => ({
    time: new Date(p.timeMs).toISOString().substring(5, 16).replace('T', ' '),
    capital: Number(p.capital.toFixed(2)),
    arb: Number(p.arbPnl.toFixed(2)),
    dir: Number(p.dirPnl.toFixed(2)),
  }));

  return (
    <Card>
      <Alert type="info" showIcon style={{ marginBottom: 12 }}
             message="K 线级回测（MVP）"
             description="假设 GTX 100% 成交、无 fee、无排队损失。结果偏乐观 20-40%。适合参数扫描 / 相对比较，不适合精确预估。" />
      <Form layout="vertical">
        <Row gutter={16}>
          <Col xs={24} md={10}>
            <Form.Item label="回测时段">
              <RangePicker value={range} onChange={v => v && setRange(v as [Dayjs, Dayjs])} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col xs={12} md={4}>
            <Form.Item label="K 线周期">
              <Select value={interval} onChange={setInterval}
                options={['1m','5m','15m','30m','1h','4h'].map(v => ({ value: v, label: v }))} />
            </Form.Item>
          </Col>
          <Col xs={12} md={5}>
            <Form.Item label="初始本金">
              <InputNumber value={initialCapital} onChange={v => setInitialCapital(Number(v) || 3000)}
                min={100} step={100} style={{ width: '100%' }} addonAfter="USDC" />
            </Form.Item>
          </Col>
          <Col xs={12} md={5}>
            <Form.Item label="模拟 5 层保护">
              <Switch checked={simulateProtection} onChange={setSimulateProtection} />
            </Form.Item>
          </Col>
        </Row>
        <Space>
          <Button type="primary" icon={<ThunderboltOutlined />} onClick={onRun} loading={running}>
            开始回测
          </Button>
        </Space>
      </Form>

      {result && (
        <>
          <Row gutter={16} style={{ marginTop: 16 }}>
            <Col xs={12} md={6}>
              <Statistic title="总收益率" value={result.totalReturnPct} precision={2} suffix="%"
                valueStyle={{ color: result.totalReturnPct >= 0 ? '#3f8600' : '#cf1322' }} />
            </Col>
            <Col xs={12} md={6}>
              <Statistic title="最大回撤" value={result.maxDrawdownPct} precision={2} suffix="%"
                valueStyle={{ color: '#cf1322' }} />
            </Col>
            <Col xs={12} md={6}>
              <Statistic title="套利次数" value={result.totalRoundTrips} />
            </Col>
            <Col xs={12} md={6}>
              <Statistic title="Layer3+ 触发" value={result.layer3Triggers + result.layer4Triggers}
                valueStyle={{ color: (result.layer3Triggers + result.layer4Triggers) > 5 ? '#cf1322' : undefined }} />
            </Col>
          </Row>

          <Descriptions bordered size="small" column={4} style={{ marginTop: 16 }}>
            <Descriptions.Item label="Arb P&L">{Number(result.totalArbitragePnl).toFixed(2)}</Descriptions.Item>
            <Descriptions.Item label="Dir P&L">{Number(result.totalDirectionalPnl).toFixed(2)}</Descriptions.Item>
            <Descriptions.Item label="Fee">{Number(result.totalFeePaid).toFixed(2)}</Descriptions.Item>
            <Descriptions.Item label="Final Cap">{Number(result.finalCapital).toFixed(2)}</Descriptions.Item>
            <Descriptions.Item label="Open fills">{result.totalOpenFills}</Descriptions.Item>
            <Descriptions.Item label="Close fills">{result.totalCloseFills}</Descriptions.Item>
            <Descriptions.Item label="Max Long">{Number(result.maxLongQty).toFixed(4)}</Descriptions.Item>
            <Descriptions.Item label="Max Short">{Number(result.maxShortQty).toFixed(4)}</Descriptions.Item>
            <Descriptions.Item label="L2 触发">{result.layer2Triggers}</Descriptions.Item>
            <Descriptions.Item label="L3 触发">{result.layer3Triggers}</Descriptions.Item>
            <Descriptions.Item label="L4 触发">{result.layer4Triggers}</Descriptions.Item>
            <Descriptions.Item label="最大 Net">{Number(result.maxNetQty).toFixed(4)}</Descriptions.Item>
          </Descriptions>

          {chartData.length > 0 && (
            <div style={{ marginTop: 16 }}>
              <h4>权益曲线</h4>
              <ResponsiveContainer width="100%" height={300}>
                <LineChart data={chartData} margin={{ top: 5, right: 30, left: 0, bottom: 5 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke="#eee" />
                  <XAxis dataKey="time" tick={{ fontSize: 10 }} />
                  <YAxis tick={{ fontSize: 11 }} />
                  <Tooltip />
                  <Legend />
                  <Line type="monotone" dataKey="capital" stroke="#722ed1" name="Equity" dot={false} strokeWidth={2} />
                  <Line type="monotone" dataKey="arb" stroke="#52c41a" name="Arb P&L" dot={false} />
                  <Line type="monotone" dataKey="dir" stroke="#faad14" name="Dir P&L" dot={false} />
                </LineChart>
              </ResponsiveContainer>
            </div>
          )}
        </>
      )}
    </Card>
  );
};
