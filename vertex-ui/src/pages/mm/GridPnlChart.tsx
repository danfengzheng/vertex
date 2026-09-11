import { GridPnlSnapshotVO } from '../../api/mmGrid';
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer } from 'recharts';
import { Empty } from 'antd';

/**
 * 快照序列 → P&L 曲线（Arb / Dir / Net / Unrealized）
 */
export const GridPnlChart = ({ data }: { data: GridPnlSnapshotVO[] }) => {
  if (!data || data.length === 0) return <Empty description="暂无快照数据" />;

  // 后端返回按 snapshotAt DESC，反转为时间升序
  const sorted = [...data].reverse();

  const chartData = sorted.map(p => ({
    time: p.snapshotAt?.substring(11, 19),
    arb: Number(p.realizedArbitragePnl ?? 0),
    dir: Number(p.realizedDirectionalPnl ?? 0),
    unrealized: Number(p.unrealizedPnl ?? 0),
    net: Number(p.realizedArbitragePnl ?? 0)
       + Number(p.realizedDirectionalPnl ?? 0)
       - Number(p.feePaid ?? 0),
  }));

  return (
    <ResponsiveContainer width="100%" height={280}>
      <LineChart data={chartData} margin={{ top: 5, right: 30, left: 0, bottom: 5 }}>
        <CartesianGrid strokeDasharray="3 3" stroke="#eee" />
        <XAxis dataKey="time" tick={{ fontSize: 11 }} />
        <YAxis tick={{ fontSize: 11 }} />
        <Tooltip />
        <Legend />
        <Line type="monotone" dataKey="arb" stroke="#52c41a" name="Arb P&L" dot={false} strokeWidth={2} />
        <Line type="monotone" dataKey="dir" stroke="#faad14" name="Dir P&L" dot={false} strokeWidth={2} />
        <Line type="monotone" dataKey="unrealized" stroke="#1890ff" name="Unrealized" dot={false} strokeDasharray="4 4" />
        <Line type="monotone" dataKey="net" stroke="#722ed1" name="Net" dot={false} strokeWidth={2} />
      </LineChart>
    </ResponsiveContainer>
  );
};

/**
 * 双侧耗尽率曲线（LONG / SHORT）
 */
export const GridDepletionChart = ({ data }: { data: GridPnlSnapshotVO[] }) => {
  if (!data || data.length === 0) return <Empty description="暂无数据" />;
  const sorted = [...data].reverse();
  const chartData = sorted.map(p => ({
    time: p.snapshotAt?.substring(11, 19),
    long: Number(p.longDepletionPct ?? 0),
    short: Number(p.shortDepletionPct ?? 0),
    score: Number(p.trendScore ?? 0),
  }));
  return (
    <ResponsiveContainer width="100%" height={220}>
      <LineChart data={chartData} margin={{ top: 5, right: 30, left: 0, bottom: 5 }}>
        <CartesianGrid strokeDasharray="3 3" stroke="#eee" />
        <XAxis dataKey="time" tick={{ fontSize: 11 }} />
        <YAxis tick={{ fontSize: 11 }} domain={[0, 100]} />
        <Tooltip />
        <Legend />
        <Line type="monotone" dataKey="long" stroke="#52c41a" name="LONG 耗尽%" dot={false} strokeWidth={2} />
        <Line type="monotone" dataKey="short" stroke="#cf1322" name="SHORT 耗尽%" dot={false} strokeWidth={2} />
        <Line type="monotone" dataKey="score" stroke="#722ed1" name="Trend Score×10" dot={false} strokeDasharray="4 4" />
      </LineChart>
    </ResponsiveContainer>
  );
};
