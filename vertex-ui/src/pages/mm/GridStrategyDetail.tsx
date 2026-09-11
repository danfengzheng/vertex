import { useEffect, useState } from 'react';
import { Card, Row, Col, Statistic, Tabs, Table, Tag, Space, Progress, Empty, Alert, Descriptions, Button, message } from 'antd';
import {
  ArrowLeftOutlined, ReloadOutlined, ArrowUpOutlined,
} from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import {
  mmGridApi, GridStatusVO, GridOrderVO, GridEventVO, GridPnlSnapshotVO, ProtectionLayer,
} from '../../api/mmGrid';
import { GridPnlChart, GridDepletionChart } from './GridPnlChart';
import { GridBacktestPanel } from './GridBacktestPanel';

const LAYER_COLOR: Record<ProtectionLayer, string> = {
  NORMAL: 'success', LAYER1: 'processing', LAYER2: 'warning',
  LAYER3: 'orange', LAYER4: 'error', EMERGENCY: 'error',
};

const SEVERITY_COLOR: Record<string, string> = { INFO: 'blue', WARN: 'orange', ERROR: 'red' };

/**
 * 网格策略详情页 —— 挂单矩阵 + 趋势雷达 + P&L 曲线 + 事件流水
 */
export const GridStrategyDetail = () => {
  const nav = useNavigate();
  const { id } = useParams<{ id: string }>();
  // 保持字符串类型 —— Long 型 id 超过 Number.MAX_SAFE_INTEGER，Number() 会丢精度导致 404
  const sid = id ?? '';

  const [status, setStatus] = useState<GridStatusVO | null>(null);
  const [orders, setOrders] = useState<GridOrderVO[]>([]);
  const [events, setEvents] = useState<GridEventVO[]>([]);
  const [pnl, setPnl] = useState<GridPnlSnapshotVO[]>([]);
  const [loading, setLoading] = useState(false);

  const load = async () => {
    if (!sid) return;
    setLoading(true);
    try {
      const [s, o, e, p] = await Promise.all([
        mmGridApi.status(sid),
        mmGridApi.orders(sid, { status: 'LIVE', limit: 100 }),
        mmGridApi.events(sid, 100),
        mmGridApi.pnl(sid, 200),
      ]);
      if (s.code === 200) setStatus(s.data ?? null);
      if (o.code === 200) setOrders(o.data ?? []);
      if (e.code === 200) setEvents(e.data ?? []);
      if (p.code === 200) setPnl(p.data ?? []);
    } catch (err) {
      message.error(err instanceof Error ? err.message : 'load failed');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
    const timer = setInterval(load, 30_000);   // 30s：4 个 API/次 → 每 30s 4 请求
    return () => clearInterval(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sid]);

  if (!status) {
    return <div>Loading...</div>;
  }
  const s = status.strategy;
  const d = status.depletion;
  // 高频运行时字段：优先取 status.realtime*（内存实时值），fallback 到 DB 快照
  const rtCenter = status.realtimeCenterPrice ?? s.centerPrice;
  const rtStep = status.realtimeStepPct ?? s.stepPct ?? s.baseStepPct;
  const rtLong = status.realtimeLongQty ?? s.currentLongQty ?? 0;
  const rtShort = status.realtimeShortQty ?? s.currentShortQty ?? 0;

  // 挂单矩阵：按价格分组
  const upperOrders = orders
    .filter(o => o.role === 'OPEN' && o.positionSide === 'SHORT')
    .sort((a, b) => Number(a.gridPrice) - Number(b.gridPrice));
  const lowerOrders = orders
    .filter(o => o.role === 'OPEN' && o.positionSide === 'LONG')
    .sort((a, b) => Number(b.gridPrice) - Number(a.gridPrice));
  const closeOrders = orders.filter(o => o.role === 'CLOSE' || o.role === 'LADDER_CLOSE');
  const ladderCount = orders.filter(o => o.role === 'LADDER_CLOSE').length;

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Space>
          <Button icon={<ArrowLeftOutlined />} onClick={() => nav('/mm/grid')}>返回</Button>
          <h2 style={{ margin: 0 }}>{s.name}</h2>
          {s.executionMode === 'PAPER' && <Tag color="purple">PAPER</Tag>}
          {s.executionMode === 'LIVE' && <Tag color="magenta">LIVE</Tag>}
          <Tag color={s.status === 'RUNNING' ? 'success' : s.status === 'EMERGENCY_STOPPED' ? 'error' : 'default'}>
            {s.status}
          </Tag>
          <Tag color={LAYER_COLOR[status.currentLayer] || 'default'}>{status.currentLayer}</Tag>
        </Space>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={load} loading={loading}>刷新</Button>
        </Space>
      </div>

      {status.currentLayer !== 'NORMAL' && (
        <Alert type="warning" showIcon style={{ marginBottom: 12 }}
               message={`保护层已激活: ${status.currentLayer}`}
               description={
                 <div>
                   <div>Trend Score: {d?.trendScore ?? 0}</div>
                   {d?.breakdown && (
                     <div>
                       ADX: {d.breakdown.adxScore ?? 0} · Depletion: {d.breakdown.depletionScore ?? 0}
                       · Asymmetry: {d.breakdown.asymmetryScore ?? 0}
                       · Consecutive: {d.breakdown.consecutiveScore ?? 0}
                       · ReverseGap: {d.breakdown.reverseGapScore ?? 0}
                     </div>
                   )}
                 </div>
               } />
      )}

      {/* 顶部关键指标 */}
      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col xs={12} md={6}>
          <Card>
            <Statistic title="Mark Price" value={Number(status.markPrice ?? 0)} precision={2} />
            <div style={{ fontSize: 12, color: '#888' }}>
              spread: {status.spreadPct != null ? Number(status.spreadPct).toFixed(4) + '%' : '-'}
            </div>
          </Card>
        </Col>
        <Col xs={12} md={6}>
          <Card>
            <Statistic title="Center Price" value={Number(rtCenter ?? 0)} precision={2}
                       prefix={<ArrowUpOutlined style={{ fontSize: 14 }} />} />
            <div style={{ fontSize: 12, color: '#888' }}>step: {Number(rtStep ?? 0).toFixed(3)}%</div>
          </Card>
        </Col>
        <Col xs={12} md={6}>
          <Card>
            <Statistic title="Active Orders" value={status.activeOrders} />
            <div style={{ fontSize: 12, color: '#888' }}>
              LONG: {status.longActiveOrders} · SHORT: {status.shortActiveOrders}
              {ladderCount > 0 && ` · Ladder: ${ladderCount}`}
            </div>
          </Card>
        </Col>
        <Col xs={12} md={6}>
          <Card>
            <Statistic title="持仓 Net Qty"
                       value={Number(rtLong) - Number(rtShort)}
                       precision={4}
                       valueStyle={{ color: (Number(rtLong) - Number(rtShort)) >= 0 ? '#3f8600' : '#cf1322' }} />
            <div style={{ fontSize: 12, color: '#888' }}>
              L: {Number(rtLong)} · S: {Number(rtShort)}
            </div>
          </Card>
        </Col>
      </Row>

      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col xs={24} md={12}>
          <Card size="small" title="趋势雷达（5 维评分）">
            {d ? (
              <>
                <div style={{ marginBottom: 8 }}>
                  <Progress percent={d.longDepletionPct} strokeColor="#3f8600"
                            format={p => `LONG 耗尽 ${(p ?? 0).toFixed(0)}%`} />
                </div>
                <div style={{ marginBottom: 8 }}>
                  <Progress percent={d.shortDepletionPct} strokeColor="#cf1322"
                            format={p => `SHORT 耗尽 ${(p ?? 0).toFixed(0)}%`} />
                </div>
                <Descriptions size="small" column={2} bordered>
                  <Descriptions.Item label="Trend Score">{d.trendScore ?? 0} / 10</Descriptions.Item>
                  <Descriptions.Item label="Asymmetry">{Number(d.asymmetryRatio ?? 0).toFixed(2)}</Descriptions.Item>
                  <Descriptions.Item label="连续同侧">{d.consecutiveSameSide ?? 0}</Descriptions.Item>
                  <Descriptions.Item label="上次反向">{d.secondsSinceLastReverseFill ?? 0}s</Descriptions.Item>
                  <Descriptions.Item label="L 速率/min">{Number(d.longSpeedPer3Min ?? 0).toFixed(2)}</Descriptions.Item>
                  <Descriptions.Item label="S 速率/min">{Number(d.shortSpeedPer3Min ?? 0).toFixed(2)}</Descriptions.Item>
                </Descriptions>
              </>
            ) : <Empty description="暂无数据" />}
          </Card>
        </Col>
        <Col xs={24} md={12}>
          <Card size="small" title="P&L 归因">
            <Descriptions size="small" column={2} bordered>
              <Descriptions.Item label="Arb P&L (累计)" span={2}>
                <span style={{ color: Number(s.totalArbitragePnl ?? 0) >= 0 ? '#3f8600' : '#cf1322', fontSize: 18, fontWeight: 600 }}>
                  {Number(s.totalArbitragePnl ?? 0).toFixed(2)} USDC
                </span>
              </Descriptions.Item>
              <Descriptions.Item label="Dir P&L (累计)" span={2}>
                <span style={{ color: Number(s.totalDirectionalPnl ?? 0) >= 0 ? '#3f8600' : '#cf1322' }}>
                  {Number(s.totalDirectionalPnl ?? 0).toFixed(2)} USDC
                </span>
              </Descriptions.Item>
              <Descriptions.Item label="Fee 累计">{Number(s.totalFeePaid ?? 0).toFixed(4)}</Descriptions.Item>
              <Descriptions.Item label="Net = Arb + Dir - Fee">
                {(Number(s.totalArbitragePnl ?? 0) + Number(s.totalDirectionalPnl ?? 0) - Number(s.totalFeePaid ?? 0)).toFixed(2)}
              </Descriptions.Item>
            </Descriptions>
          </Card>
        </Col>
      </Row>

      <Tabs
        items={[
          {
            key: 'orders',
            label: `活跃挂单 (${orders.length})`,
            children: (
              <Card>
                <Row gutter={16}>
                  <Col xs={24} md={12}>
                    <h4 style={{ color: '#cf1322' }}>SELL grid (SHORT 侧) - {upperOrders.length}</h4>
                    <MatrixTable orders={upperOrders} center={Number(rtCenter ?? 0)} />
                  </Col>
                  <Col xs={24} md={12}>
                    <h4 style={{ color: '#3f8600' }}>BUY grid (LONG 侧) - {lowerOrders.length}</h4>
                    <MatrixTable orders={lowerOrders} center={Number(rtCenter ?? 0)} />
                  </Col>
                </Row>
                {closeOrders.length > 0 && (
                  <>
                    <h4 style={{ marginTop: 16 }}>平仓单 + 梯队 ({closeOrders.length})</h4>
                    <Table
                      size="small" pagination={false} rowKey="id"
                      dataSource={closeOrders}
                      columns={[
                        { title: 'Price', dataIndex: 'gridPrice', key: 'p',
                          render: (v: number) => Number(v).toFixed(2) },
                        { title: 'Side', dataIndex: 'side', key: 'side' },
                        { title: 'Pos', dataIndex: 'positionSide', key: 'ps',
                          render: (v: string) => <Tag color={v === 'LONG' ? 'green' : 'red'}>{v}</Tag> },
                        { title: 'Qty', dataIndex: 'qty', key: 'qty' },
                        { title: 'Role', dataIndex: 'role', key: 'role',
                          render: (r: string) => r === 'LADDER_CLOSE' ? <Tag color="gold">Ladder</Tag> : r },
                      ]}
                    />
                  </>
                )}
              </Card>
            ),
          },
          {
            key: 'events',
            label: `事件流水 (${events.length})`,
            children: (
              <Card>
                <Table size="small" pagination={{ pageSize: 20 }} rowKey="id" dataSource={events}
                  columns={[
                    { title: 'Time', dataIndex: 'occurredAt', key: 't', width: 160,
                      render: (v: string) => v?.replace('T', ' ').substring(0, 19) },
                    { title: 'Type', dataIndex: 'eventType', key: 'e', width: 180 },
                    { title: 'Severity', dataIndex: 'severity', key: 's', width: 90,
                      render: (v: string) => <Tag color={SEVERITY_COLOR[v] || 'default'}>{v}</Tag> },
                    { title: 'Payload', dataIndex: 'payloadJson', key: 'p',
                      render: (v: string) => <code style={{ fontSize: 11 }}>{v}</code> },
                  ]} />
              </Card>
            ),
          },
          {
            key: 'pnl',
            label: `P&L 快照 (${pnl.length})`,
            children: (
              <>
                <Card size="small" title="P&L 曲线" style={{ marginBottom: 12 }}>
                  <GridPnlChart data={pnl} />
                </Card>
                <Card size="small" title="Depletion + Trend Score" style={{ marginBottom: 12 }}>
                  <GridDepletionChart data={pnl} />
                </Card>
                <Card size="small" title="快照明细">
                  <Table size="small" pagination={{ pageSize: 10 }} rowKey="id" dataSource={pnl}
                    columns={[
                      { title: 'Time', dataIndex: 'snapshotAt', key: 't', width: 160,
                        render: (v: string) => v?.replace('T', ' ').substring(0, 19) },
                      { title: 'Mark', dataIndex: 'markPrice', key: 'm',
                        render: (v: number) => v ? Number(v).toFixed(2) : '-' },
                      { title: 'Long qty', dataIndex: 'longQty', key: 'lq' },
                      { title: 'Short qty', dataIndex: 'shortQty', key: 'sq' },
                      { title: 'Net', dataIndex: 'netQty', key: 'n' },
                      { title: 'Unrealized', dataIndex: 'unrealizedPnl', key: 'u',
                        render: (v: number) => v == null ? '-' :
                          <span style={{ color: Number(v) >= 0 ? '#3f8600' : '#cf1322' }}>{Number(v).toFixed(2)}</span> },
                      { title: 'Arb', dataIndex: 'realizedArbitragePnl', key: 'a',
                        render: (v: number) => v == null ? '-' : Number(v).toFixed(2) },
                      { title: 'Layer', dataIndex: 'currentLayer', key: 'l',
                        render: (v: ProtectionLayer) => <Tag color={LAYER_COLOR[v] || 'default'}>{v}</Tag> },
                      { title: 'Score', dataIndex: 'trendScore', key: 'ts' },
                    ]} />
                </Card>
              </>
            ),
          },
          {
            key: 'backtest',
            label: '回测',
            children: <GridBacktestPanel strategyId={sid} />,
          },
        ]}
      />
    </div>
  );
};

/** 挂单矩阵简易表格 */
const MatrixTable = ({ orders, center }: { orders: GridOrderVO[]; center: number }) => (
  <Table size="small" pagination={false} rowKey="id" dataSource={orders}
    columns={[
      { title: 'Price', dataIndex: 'gridPrice', key: 'p',
        render: (v: number) => {
          const diff = ((Number(v) - center) / center * 100).toFixed(2);
          return <>{Number(v).toFixed(2)} <small style={{ color: '#888' }}>({diff}%)</small></>;
        } },
      { title: 'Qty', dataIndex: 'qty', key: 'qty' },
      { title: 'Idx', dataIndex: 'gridIndex', key: 'idx' },
      { title: 'Status', dataIndex: 'status', key: 's',
        render: (v: string) => <Tag color={v === 'LIVE' ? 'green' : 'default'}>{v}</Tag> },
    ]}
  />
);
