import { useState } from 'react';
import {
  Card, Row, Col, Form, DatePicker, InputNumber, Button, Statistic, Table, Tag,
  message, Alert, Spin, Space,
} from 'antd';
import { ThunderboltOutlined } from '@ant-design/icons';
import type { Dayjs } from 'dayjs';
import dayjs from 'dayjs';
import { formatTimestamp } from '../../utils/date';
import {
  farbBacktestApi, BacktestConfig, BacktestResult,
} from '../../api/farb';

const { RangePicker } = DatePicker;

/** 资金费套利 - 回测面板 */
export const FarbBacktest = () => {
  const [form] = Form.useForm();
  const [running, setRunning] = useState(false);
  const [result, setResult] = useState<BacktestResult | null>(null);

  const handleRun = async () => {
    try {
      const v = await form.validateFields();
      const [start, end] = v.timeRange as [Dayjs, Dayjs];
      const config: BacktestConfig = {
        startTimeMs: start.valueOf(),
        endTimeMs: end.valueOf(),
        initialCapital: v.initialCapital,
        notionalPerPair: v.notionalPerPair,
        maxOpenPairs: v.maxOpenPairs,
        maxPairsPerSymbol: v.maxPairsPerSymbol,
        lookbackSettlements: v.lookbackSettlements,
        minAvgFundingBps: v.minAvgFundingBps,
        minHitRatio: v.minHitRatio,
        minLatestFundingBps: v.minLatestFundingBps,
        exitRegimeBreakCount: v.exitRegimeBreakCount,
        exitTakeProfitPct: v.exitTakeProfitPct,
        exitMaxHoldHours: v.exitMaxHoldHours,
        spotFeeRate: v.spotFeeRate,
        perpFeeRate: v.perpFeeRate,
        slippagePerLegBps: v.slippagePerLegBps,
        ignoreBasisPnl: v.ignoreBasisPnl,
      };
      setRunning(true);
      const res = await farbBacktestApi.run(config);
      if (res.code === 200 && res.data) {
        setResult(res.data);
        message.success(`回测完成，${res.data.totalPairsClosed} 笔交易`);
      } else {
        message.error(res.message || '回测失败');
      }
    } catch (e: any) {
      if (!e?.errorFields) message.error(e?.message ?? '回测失败');
    } finally {
      setRunning(false);
    }
  };

  return (
    <div>
      <Card
        title="资金费套利 - 历史回测"
        extra={
          <Button
            type="primary"
            icon={<ThunderboltOutlined />}
            loading={running}
            onClick={handleRun}
          >
            运行回测
          </Button>
        }
      >
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message="回测数据来自 RocksDB 累积的信号"
          description="首次部署 DataCollector 需 3-6 个月积累才能得到有意义的回测。当前简化实现只用 funding 历史，不含 basis / OI / LSR 信号。"
        />
        <Form
          form={form}
          layout="vertical"
          initialValues={{
            timeRange: [dayjs().subtract(30, 'day'), dayjs()],
            initialCapital: 10000,
            notionalPerPair: 500,
            maxOpenPairs: 8,
            maxPairsPerSymbol: 1,
            lookbackSettlements: 6,
            minAvgFundingBps: 4.0,
            minHitRatio: 0.67,
            minLatestFundingBps: 2.0,
            exitRegimeBreakCount: 2,
            exitTakeProfitPct: 0,   // 资金费套利建议禁用 TP（0 = 不触发，让 regime 自然出场）
            exitMaxHoldHours: 9999,
            spotFeeRate: 0.00075,
            perpFeeRate: 0.00036,
            slippagePerLegBps: 5,
            ignoreBasisPnl: true,
          }}
        >
          <Row gutter={16}>
            <Col xs={24} md={12}>
              <Form.Item name="timeRange" label="时间区间" rules={[{ required: true }]}>
                <RangePicker showTime style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item name="initialCapital" label="初始资金">
                <InputNumber min={100} step={1000} style={{ width: '100%' }} addonAfter="USD" />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item name="notionalPerPair" label="单对名义">
                <InputNumber min={10} step={100} style={{ width: '100%' }} addonAfter="USD" />
              </Form.Item>
            </Col>
            <Col xs={12} md={4}>
              <Form.Item name="maxOpenPairs" label="Max open">
                <InputNumber min={1} max={50} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col xs={12} md={4}>
              <Form.Item name="maxPairsPerSymbol" label="Max per symbol">
                <InputNumber min={1} max={5} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col xs={12} md={4}>
              <Form.Item name="lookbackSettlements" label="Lookback">
                <InputNumber min={2} max={24} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col xs={12} md={4}>
              <Form.Item name="minAvgFundingBps" label="Min avg funding">
                <InputNumber min={0} step={0.5} style={{ width: '100%' }} addonAfter="bps" />
              </Form.Item>
            </Col>
            <Col xs={12} md={4}>
              <Form.Item name="minHitRatio" label="Min hit ratio">
                <InputNumber min={0} max={1} step={0.05} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col xs={12} md={4}>
              <Form.Item name="exitTakeProfitPct" label="TP (0=禁用)">
                <InputNumber min={0} step={0.1} style={{ width: '100%' }} addonAfter="%" />
              </Form.Item>
            </Col>
            <Col xs={12} md={4}>
              <Form.Item name="exitMaxHoldHours" label="Max hold">
                <InputNumber min={4} step={12} style={{ width: '100%' }} addonAfter="h" />
              </Form.Item>
            </Col>
            <Col xs={12} md={4}>
              <Form.Item name="slippagePerLegBps" label="Slippage/leg">
                <InputNumber min={0} max={100} style={{ width: '100%' }} addonAfter="bps" />
              </Form.Item>
            </Col>
          </Row>
        </Form>
      </Card>

      {running && (
        <Card style={{ marginTop: 16, textAlign: 'center' }}>
          <Spin size="large" tip="回测运行中..." />
        </Card>
      )}

      {result && (
        <div style={{ marginTop: 16 }}>
          {result.warnings.length > 0 && (
            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 16 }}
              message="数据完整性提示"
              description={
                <ul>
                  {result.warnings.map((w, i) => <li key={i}>{w}</li>)}
                </ul>
              }
            />
          )}

          <Card title="回测统计" style={{ marginBottom: 16 }}>
            <Row gutter={16}>
              <Col span={4}>
                <Statistic
                  title="总收益"
                  value={result.totalReturnPct}
                  precision={2}
                  suffix="%"
                  valueStyle={{ color: result.totalReturnPct >= 0 ? '#3f8600' : '#cf1322' }}
                />
              </Col>
              <Col span={4}>
                <Statistic
                  title="年化收益"
                  value={result.annualizedReturnPct}
                  precision={1}
                  suffix="%"
                  valueStyle={{ color: result.annualizedReturnPct >= 0 ? '#3f8600' : '#cf1322' }}
                />
              </Col>
              <Col span={4}>
                <Statistic
                  title="Sharpe"
                  value={result.sharpe}
                  precision={2}
                  valueStyle={{ color: result.sharpe >= 1 ? '#3f8600' : '#faad14' }}
                />
              </Col>
              <Col span={4}>
                <Statistic
                  title="最大回撤"
                  value={result.maxDrawdownPct}
                  precision={2}
                  suffix="%"
                  valueStyle={{ color: '#cf1322' }}
                />
              </Col>
              <Col span={4}>
                <Statistic title="胜率" value={result.winRatePct} precision={1} suffix="%" />
              </Col>
              <Col span={4}>
                <Statistic title="总交易" value={result.totalPairsClosed} />
              </Col>
            </Row>
            <Row gutter={16} style={{ marginTop: 16 }}>
              <Col span={6}>
                <Statistic title="Funding PnL" value={result.totalFundingPnl}
                  precision={2} valueStyle={{ color: '#3f8600' }} suffix="USD" />
              </Col>
              <Col span={6}>
                <Statistic title="Basis PnL" value={result.totalBasisPnl}
                  precision={2} suffix="USD" />
              </Col>
              <Col span={6}>
                <Statistic title="累计手续费" value={result.totalFeePaid}
                  precision={2} valueStyle={{ color: '#cf1322' }} suffix="USD" />
              </Col>
              <Col span={6}>
                <Statistic title="平均单对 PnL" value={result.avgPairNetPnl}
                  precision={2} suffix="USD" />
              </Col>
              <Col span={6} style={{ marginTop: 16 }}>
                <Statistic title="平均持仓" value={result.avgHoldingHours} precision={1} suffix="h" />
              </Col>
              <Col span={6} style={{ marginTop: 16 }}>
                <Statistic title="平均结算次数" value={result.avgSettlementsPerPair} precision={2} />
              </Col>
              <Col span={12} style={{ marginTop: 16 }}>
                <div>出场原因分布：</div>
                <Space wrap>
                  {Object.entries(result.closeTriggerDistribution || {}).map(([k, v]) => (
                    <Tag key={k}>{k}: {v}</Tag>
                  ))}
                </Space>
              </Col>
            </Row>
          </Card>

          <Row gutter={16}>
            <Col xs={24} md={12}>
              <Card title={`币种收益 Top 10 (${result.topSymbols.length})`} size="small">
                <Table
                  rowKey="symbol"
                  size="small"
                  pagination={false}
                  dataSource={result.topSymbols}
                  columns={[
                    { title: 'Symbol', dataIndex: 'symbol' },
                    { title: '笔数', dataIndex: 'pairCount', width: 60 },
                    {
                      title: '总 PnL',
                      dataIndex: 'totalNetPnl',
                      width: 100,
                      render: (v: number) => (
                        <span style={{ color: v >= 0 ? '#3f8600' : '#cf1322' }}>
                          {v.toFixed(2)}
                        </span>
                      ),
                    },
                    {
                      title: '胜率',
                      dataIndex: 'winRatePct',
                      width: 80,
                      render: (v: number) => `${v.toFixed(1)}%`,
                    },
                  ]}
                />
              </Card>
            </Col>
            <Col xs={24} md={12}>
              <Card title={`币种收益 Bottom 10`} size="small">
                <Table
                  rowKey="symbol"
                  size="small"
                  pagination={false}
                  dataSource={result.bottomSymbols}
                  columns={[
                    { title: 'Symbol', dataIndex: 'symbol' },
                    { title: '笔数', dataIndex: 'pairCount', width: 60 },
                    {
                      title: '总 PnL',
                      dataIndex: 'totalNetPnl',
                      width: 100,
                      render: (v: number) => (
                        <span style={{ color: v >= 0 ? '#3f8600' : '#cf1322' }}>
                          {v.toFixed(2)}
                        </span>
                      ),
                    },
                    {
                      title: '胜率',
                      dataIndex: 'winRatePct',
                      width: 80,
                      render: (v: number) => `${v.toFixed(1)}%`,
                    },
                  ]}
                />
              </Card>
            </Col>
          </Row>

          <Card title={`交易明细（${result.trades.length}）`} style={{ marginTop: 16 }} size="small">
            <Table
              rowKey={(r) => `${r.symbol}-${r.entryTs}`}
              size="small"
              dataSource={result.trades}
              pagination={{ pageSize: 50 }}
              columns={[
                { title: 'Symbol', dataIndex: 'symbol', width: 100 },
                {
                  title: '入场',
                  dataIndex: 'entryTs',
                  width: 150,
                  render: (v: number | string) => formatTimestamp(v),
                },
                {
                  title: '出场',
                  dataIndex: 'exitTs',
                  width: 150,
                  render: (v: number | string) => formatTimestamp(v),
                },
                { title: '持仓 h', dataIndex: 'holdHours', width: 70 },
                { title: '结算次数', dataIndex: 'settlementCount', width: 80 },
                {
                  title: 'Funding',
                  dataIndex: 'fundingPnl',
                  width: 100,
                  render: (v: number) => v.toFixed(3),
                },
                {
                  title: '手续费',
                  dataIndex: 'feePaid',
                  width: 100,
                  render: (v: number) => v.toFixed(3),
                },
                {
                  title: '净 PnL',
                  dataIndex: 'netPnl',
                  width: 100,
                  render: (v: number) => (
                    <span style={{ color: v >= 0 ? '#3f8600' : '#cf1322', fontWeight: 500 }}>
                      {v.toFixed(3)}
                    </span>
                  ),
                },
                { title: '触发', dataIndex: 'closeTrigger', width: 140 },
              ]}
            />
          </Card>
        </div>
      )}
    </div>
  );
};

export default FarbBacktest;
