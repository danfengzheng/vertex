import { useEffect, useState } from 'react';
import {
  Card, Row, Col, Statistic, Tag, Tabs, Table, Button, Space, message,
  Popconfirm, Descriptions,
} from 'antd';
import { ArrowLeftOutlined, ReloadOutlined, CloseCircleOutlined } from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import { formatTimestamp, formatServerTime } from '../../utils/date';
import {
  farbApi, FarbStrategyVO, FarbPairVO, FarbEventLogVO, FarbSettlementLogVO,
  ArbCandidateVO,
} from '../../api/farb';

/** 资金费套利 - 策略详情页 */
export const FarbStrategyDetail = () => {
  const nav = useNavigate();
  const { id } = useParams<{ id: string }>();

  const [strategy, setStrategy] = useState<FarbStrategyVO | null>(null);
  const [candidates, setCandidates] = useState<ArbCandidateVO[]>([]);
  const [candidatesTs, setCandidatesTs] = useState<number | null>(null);
  const [pairs, setPairs] = useState<FarbPairVO[]>([]);
  const [events, setEvents] = useState<FarbEventLogVO[]>([]);
  const [settlements, setSettlements] = useState<FarbSettlementLogVO[]>([]);

  const load = async () => {
    if (!id) return;
    try {
      const [s, c, p, e] = await Promise.all([
        farbApi.get(id),
        farbApi.candidates(id),
        farbApi.pairs(id, { limit: 200 }),
        farbApi.events(id, 200),
      ]);
      if (s.code === 200) setStrategy(s.data);
      if (c.code === 200) {
        setCandidates(c.data?.candidates || []);
        setCandidatesTs(c.data?.lastScanAt || null);
      }
      if (p.code === 200) setPairs(p.data || []);
      if (e.code === 200) setEvents(e.data || []);
      // 汇总所有有过结算的 pair（含 EMERGENCY_CLOSED —— 曾经开过就应该看到历史）
      const pairsWithHistory = (p.data || []).filter(
        (x) => x.status !== 'CANCELED' && x.status !== 'PENDING_ENTRY',
      );
      if (pairsWithHistory.length > 0) {
        const settleResults = await Promise.all(
          pairsWithHistory.slice(0, 50).map((x) => farbApi.settlements(x.id)),
        );
        const flat = settleResults.flatMap((r) => r.data || []);
        setSettlements(flat);
      }
    } catch (ex) {
      console.warn('load failed', ex);
    }
  };

  useEffect(() => {
    load();
    const t = setInterval(load, 15_000);
    return () => clearInterval(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);

  const handleManualClose = async (pairId: string) => {
    try {
      await farbApi.manualClose(pairId);
      message.success('已发起平仓');
      load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : 'close failed');
    }
  };

  if (!strategy) {
    return (
      <Card loading>
        <div style={{ height: 200 }} />
      </Card>
    );
  }

  // ─── Statistics ─────────────────────────────────────────────────────
  const closedPairs = pairs.filter((p) => p.status === 'CLOSED');
  const closedWinRate = closedPairs.length > 0
    ? (closedPairs.filter((p) => (p.netPnl ?? 0) > 0).length / closedPairs.length) * 100
    : 0;
  const avgPairPnl = closedPairs.length > 0
    ? closedPairs.reduce((s, p) => s + Number(p.netPnl ?? 0), 0) / closedPairs.length
    : 0;

  return (
    <div>
      <Card
        title={
          <Space>
            <Button icon={<ArrowLeftOutlined />} onClick={() => nav('/farb')}>
              返回
            </Button>
            <span>{strategy.name}</span>
            <Tag color={strategy.executionMode === 'LIVE' ? 'red' : 'blue'}>
              {strategy.executionMode}
            </Tag>
            <Tag>{strategy.status}</Tag>
          </Space>
        }
        extra={
          <Button icon={<ReloadOutlined />} onClick={load}>
            刷新
          </Button>
        }
        style={{ marginBottom: 16 }}
      >
        <Row gutter={16}>
          <Col span={4}>
            <Statistic title="累计净 PnL" precision={2}
              value={Number(strategy.totalNetPnl ?? 0)}
              valueStyle={{ color: (strategy.totalNetPnl ?? 0) >= 0 ? '#3f8600' : '#cf1322' }}
              suffix="USDT" />
          </Col>
          <Col span={4}>
            <Statistic title="Funding PnL" precision={2}
              value={Number(strategy.totalFundingPnl ?? 0)}
              valueStyle={{ color: '#3f8600' }} suffix="USDT" />
          </Col>
          <Col span={4}>
            <Statistic title="Basis PnL" precision={2}
              value={Number(strategy.totalBasisPnl ?? 0)}
              valueStyle={{ color: (strategy.totalBasisPnl ?? 0) >= 0 ? '#3f8600' : '#cf1322' }}
              suffix="USDT" />
          </Col>
          <Col span={4}>
            <Statistic title="累计手续费" precision={2}
              value={Number(strategy.totalFeePaid ?? 0)} suffix="USDT" />
          </Col>
          <Col span={4}>
            <Statistic title="胜率（已平仓）"
              value={closedWinRate} precision={1} suffix="%"
              valueStyle={{ color: closedWinRate >= 55 ? '#3f8600' : '#faad14' }} />
          </Col>
          <Col span={4}>
            <Statistic title="平均单对 PnL" precision={2}
              value={avgPairPnl}
              valueStyle={{ color: avgPairPnl >= 0 ? '#3f8600' : '#cf1322' }} />
          </Col>
        </Row>
      </Card>

      <Tabs
        defaultActiveKey="candidates"
        items={[
          {
            key: 'candidates',
            label: `候选（${candidates.length}）`,
            children: (
              <Card size="small">
                {candidatesTs && (
                  <div style={{ marginBottom: 8, color: '#999' }}>
                    最近扫描时间：{formatTimestamp(candidatesTs)}
                  </div>
                )}
                <Table
                  rowKey="symbol"
                  size="small"
                  dataSource={candidates}
                  pagination={{ pageSize: 20 }}
                  columns={[
                    { title: 'Symbol', dataIndex: 'symbol', width: 120 },
                    {
                      title: '综合评分',
                      dataIndex: 'score',
                      width: 100,
                      render: (v: number) => (
                        <Tag color={v >= 70 ? 'success' : v >= 50 ? 'warning' : 'default'}>
                          {v.toFixed(1)}
                        </Tag>
                      ),
                    },
                    {
                      title: '近 N 次均 funding',
                      width: 130,
                      render: (r: ArbCandidateVO) =>
                        `${(r.metrics.avgRecentFundingBps ?? 0).toFixed(2)} bps`,
                    },
                    {
                      title: '命中率',
                      width: 90,
                      render: (r: ArbCandidateVO) =>
                        `${((r.metrics.hitRatio ?? 0) * 100).toFixed(0)}%`,
                    },
                    {
                      title: 'basis',
                      width: 90,
                      render: (r: ArbCandidateVO) =>
                        `${(r.metrics.currentBasisBps ?? 0).toFixed(1)} bps`,
                    },
                    {
                      title: 'LSR',
                      width: 80,
                      render: (r: ArbCandidateVO) => (r.metrics.lsr ?? 0).toFixed(2),
                    },
                    {
                      title: '预期年化',
                      width: 100,
                      render: (r: ArbCandidateVO) => (
                        <span style={{ color: '#3f8600' }}>
                          {r.expectedAnnualizedYieldPct.toFixed(1)}%
                        </span>
                      ),
                    },
                    { title: '原因', dataIndex: 'reason', ellipsis: true },
                  ]}
                />
              </Card>
            ),
          },
          {
            key: 'pairs',
            label: `持仓 pair（${pairs.length}）`,
            children: (
              <Card size="small">
                <Table
                  rowKey="id"
                  size="small"
                  dataSource={pairs}
                  pagination={{ pageSize: 20 }}
                  columns={[
                    { title: 'Symbol', dataIndex: 'symbol', width: 100 },
                    {
                      title: '状态',
                      dataIndex: 'status',
                      width: 130,
                      render: (s: string) => {
                        const color = s === 'OPEN' ? 'success'
                          : s === 'CLOSED' ? 'default'
                          : s === 'PENDING_ENTRY' ? 'processing'
                          : s === 'CLOSING' ? 'warning'
                          : 'error';
                        return <Tag color={color}>{s}</Tag>;
                      },
                    },
                    {
                      title: '入场时间',
                      dataIndex: 'entryTime',
                      width: 160,
                      render: (v: string) => formatServerTime(v),
                    },
                    {
                      title: '入场价（spot/perp）',
                      width: 160,
                      render: (r: FarbPairVO) =>
                        `${(r.entrySpotPrice ?? 0).toFixed(4)} / ${(r.entryPerpPrice ?? 0).toFixed(4)}`,
                    },
                    {
                      title: '数量',
                      width: 100,
                      render: (r: FarbPairVO) => (r.spotQty ?? 0).toFixed(6),
                    },
                    {
                      title: '结算次数',
                      width: 90,
                      render: (r: FarbPairVO) => r.settlementCount ?? 0,
                    },
                    {
                      title: 'Funding 累计',
                      width: 110,
                      render: (r: FarbPairVO) => (
                        <span style={{ color: (r.actualFundingPnl ?? 0) >= 0 ? '#3f8600' : '#cf1322' }}>
                          {(r.actualFundingPnl ?? 0).toFixed(4)}
                        </span>
                      ),
                    },
                    {
                      title: '净 PnL',
                      width: 100,
                      render: (r: FarbPairVO) => (
                        <span style={{
                          color: (r.netPnl ?? 0) >= 0 ? '#3f8600' : '#cf1322',
                          fontWeight: 500,
                        }}>
                          {(r.netPnl ?? 0).toFixed(4)}
                        </span>
                      ),
                    },
                    {
                      title: '出场原因',
                      dataIndex: 'closeTrigger',
                      width: 130,
                      render: (v: string) => v || '-',
                    },
                    {
                      title: '操作',
                      width: 120,
                      render: (r: FarbPairVO) =>
                        r.status === 'OPEN' ? (
                          <Popconfirm
                            title="确认手动平仓？"
                            onConfirm={() => handleManualClose(r.id)}
                          >
                            <Button type="link" danger size="small" icon={<CloseCircleOutlined />}>
                              平仓
                            </Button>
                          </Popconfirm>
                        ) : null,
                    },
                  ]}
                />
              </Card>
            ),
          },
          {
            key: 'settlements',
            label: `结算记账（${settlements.length}）`,
            children: (
              <Card size="small">
                <Table
                  rowKey="id"
                  size="small"
                  dataSource={settlements}
                  pagination={{ pageSize: 30 }}
                  columns={[
                    {
                      title: '时间',
                      dataIndex: 'settlementTime',
                      width: 160,
                      render: (v: string) => formatServerTime(v),
                    },
                    { title: 'Symbol', dataIndex: 'symbol', width: 100 },
                    {
                      title: 'Funding %',
                      dataIndex: 'fundingRate',
                      width: 110,
                      render: (v: number) => `${(v * 100).toFixed(4)}%`,
                    },
                    {
                      title: '周期',
                      dataIndex: 'fundingIntervalHours',
                      width: 80,
                      render: (v: number) => `${v}h`,
                    },
                    {
                      title: 'Mark 价',
                      dataIndex: 'markPriceAtSettle',
                      width: 110,
                      render: (v?: number) => (v ?? 0).toFixed(4),
                    },
                    {
                      title: '数量',
                      dataIndex: 'perpQty',
                      width: 100,
                      render: (v: number) => v.toFixed(6),
                    },
                    {
                      title: '资金金额',
                      dataIndex: 'fundingAmount',
                      width: 110,
                      render: (v: number) => (
                        <span style={{ color: v >= 0 ? '#3f8600' : '#cf1322' }}>
                          {v.toFixed(4)}
                        </span>
                      ),
                    },
                    { title: '来源', dataIndex: 'source', width: 130 },
                  ]}
                />
              </Card>
            ),
          },
          {
            key: 'events',
            label: `事件日志（${events.length}）`,
            children: (
              <Card size="small">
                <Table
                  rowKey="id"
                  size="small"
                  dataSource={events}
                  pagination={{ pageSize: 50 }}
                  columns={[
                    {
                      title: '时间',
                      dataIndex: 'occurredAt',
                      width: 160,
                      render: (v: string) => formatServerTime(v),
                    },
                    {
                      title: '级别',
                      dataIndex: 'severity',
                      width: 80,
                      render: (v: string) => {
                        const c = v === 'ERROR' ? 'red' : v === 'WARN' ? 'orange' : 'default';
                        return <Tag color={c}>{v}</Tag>;
                      },
                    },
                    { title: 'Symbol', dataIndex: 'symbol', width: 100 },
                    { title: '事件', dataIndex: 'eventType', width: 220 },
                    { title: 'Payload', dataIndex: 'payload', ellipsis: true },
                  ]}
                />
              </Card>
            ),
          },
          {
            key: 'config',
            label: '配置',
            children: (
              <Card size="small">
                <Descriptions column={2} bordered size="small">
                  <Descriptions.Item label="现货账户">{strategy.spotAccountId}</Descriptions.Item>
                  <Descriptions.Item label="期货账户">{strategy.perpAccountId}</Descriptions.Item>
                  <Descriptions.Item label="全市场扫描">{strategy.scanAllMarket === 1 ? '是' : '否'}</Descriptions.Item>
                  <Descriptions.Item label="最小 24h 成交额">{strategy.min24hVolumeUsd?.toLocaleString()} USD</Descriptions.Item>
                  <Descriptions.Item label="Lookback 结算数">{strategy.lookbackSettlements}</Descriptions.Item>
                  <Descriptions.Item label="Min 平均 funding">{strategy.minAvgFundingBps} bps</Descriptions.Item>
                  <Descriptions.Item label="Min 命中率">{strategy.minHitRatio}</Descriptions.Item>
                  <Descriptions.Item label="Max 入场 basis">{strategy.maxEntryBasisBps} bps</Descriptions.Item>
                  <Descriptions.Item label="LSR 极端阈值">{strategy.lsrMaxExtreme}</Descriptions.Item>
                  <Descriptions.Item label="OI 拥挤阈值">{strategy.oiCrowdingThresholdPct}%</Descriptions.Item>
                  <Descriptions.Item label="出场 regime break 次数">{strategy.exitRegimeBreakCount}</Descriptions.Item>
                  <Descriptions.Item label="出场 basis delta">{strategy.exitBasisDeltaBps} bps</Descriptions.Item>
                  <Descriptions.Item label="Take profit">{strategy.exitTakeProfitPct}%</Descriptions.Item>
                  <Descriptions.Item label="Max hold">{strategy.exitMaxHoldHours}h</Descriptions.Item>
                  <Descriptions.Item label="Basis 紧急阈值">{strategy.basisEmergencyBps} bps</Descriptions.Item>
                  <Descriptions.Item label="单对名义">{strategy.notionalPerPair} USD</Descriptions.Item>
                  <Descriptions.Item label="Max open pairs">{strategy.maxOpenPairs}</Descriptions.Item>
                  <Descriptions.Item label="期货杠杆">{strategy.perpLeverage}x</Descriptions.Item>
                  <Descriptions.Item label="Min 预期年化">{strategy.minExpectedYieldPct}%</Descriptions.Item>
                  <Descriptions.Item label="Max drawdown">{strategy.maxDrawdownPct}%</Descriptions.Item>
                </Descriptions>
              </Card>
            ),
          },
        ]}
      />
    </div>
  );
};

export default FarbStrategyDetail;
