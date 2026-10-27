import { useEffect, useState } from 'react';
import {
  Table, Button, Space, Tag, message, Popconfirm, Card, Row, Col, Statistic,
} from 'antd';
import {
  PlayCircleOutlined, PauseCircleOutlined,
  PlusOutlined, EditOutlined, DeleteOutlined, EyeOutlined, ReloadOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { farbApi, FarbStrategyVO, FarbStatus } from '../../api/farb';

const STATUS_COLOR: Record<FarbStatus, string> = {
  STOPPED: 'default',
  RUNNING: 'success',
  PAUSED: 'warning',
  HIBERNATE: 'blue',
};

/** 资金费套利 - 策略列表页 */
export const FarbStrategyList = () => {
  const nav = useNavigate();
  const [data, setData] = useState<FarbStrategyVO[]>([]);
  const [loading, setLoading] = useState(false);

  const load = async () => {
    setLoading(true);
    try {
      const res = await farbApi.list();
      if (res.code === 200 && res.data) setData(res.data);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
    const timer = setInterval(load, 30_000);
    return () => clearInterval(timer);
  }, []);

  const runningCount = data.filter((s) => s.status === 'RUNNING').length;
  const totalOpenPairs = data.reduce((s, x) => s + Number(x.currentOpenPairs ?? 0), 0);
  const totalNetPnl = data.reduce((s, x) => s + Number(x.totalNetPnl ?? 0), 0);
  const totalFundingPnl = data.reduce((s, x) => s + Number(x.totalFundingPnl ?? 0), 0);

  const handleStart = async (id: string) => {
    try {
      await farbApi.start(id);
      message.success('已启动');
      load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : 'start failed');
    }
  };
  const handleStop = async (id: string) => {
    try {
      await farbApi.stop(id);
      message.success('已停止');
      load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : 'stop failed');
    }
  };
  const handleDelete = async (id: string) => {
    try {
      await farbApi.delete(id);
      message.success('已删除');
      load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : 'delete failed');
    }
  };

  const columns = [
    {
      title: '策略名',
      dataIndex: 'name',
      render: (v: string, r: FarbStrategyVO) => (
        <div>
          <div style={{ fontWeight: 500 }}>{v}</div>
          {r.description && (
            <div style={{ fontSize: 12, color: '#999' }}>{r.description}</div>
          )}
        </div>
      ),
    },
    {
      title: '模式',
      dataIndex: 'executionMode',
      width: 90,
      render: (m: string) => (
        <Tag color={m === 'LIVE' ? 'red' : 'blue'}>{m}</Tag>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (s: FarbStatus) => (
        <Tag color={STATUS_COLOR[s] ?? 'default'}>{s}</Tag>
      ),
    },
    {
      title: '开仓中',
      dataIndex: 'currentOpenPairs',
      width: 80,
      render: (n?: number, r?: FarbStrategyVO) =>
        `${n ?? 0} / ${r?.maxOpenPairs ?? 0}`,
    },
    {
      title: '累计 Funding PnL',
      dataIndex: 'totalFundingPnl',
      width: 130,
      render: (v?: number) => (
        <span style={{ color: (v ?? 0) >= 0 ? '#3f8600' : '#cf1322' }}>
          {(v ?? 0).toFixed(2)}
        </span>
      ),
    },
    {
      title: '累计 Basis PnL',
      dataIndex: 'totalBasisPnl',
      width: 130,
      render: (v?: number) => (
        <span style={{ color: (v ?? 0) >= 0 ? '#3f8600' : '#cf1322' }}>
          {(v ?? 0).toFixed(2)}
        </span>
      ),
    },
    {
      title: '累计手续费',
      dataIndex: 'totalFeePaid',
      width: 110,
      render: (v?: number) => (v ?? 0).toFixed(2),
    },
    {
      title: '净 PnL',
      dataIndex: 'totalNetPnl',
      width: 100,
      render: (v?: number) => (
        <span style={{ color: (v ?? 0) >= 0 ? '#3f8600' : '#cf1322', fontWeight: 500 }}>
          {(v ?? 0).toFixed(2)}
        </span>
      ),
    },
    {
      title: '开/平次数',
      width: 100,
      render: (r: FarbStrategyVO) =>
        `${r.totalPairsOpened ?? 0} / ${r.totalPairsClosed ?? 0}`,
    },
    {
      title: '操作',
      width: 260,
      render: (r: FarbStrategyVO) => (
        <Space size={4}>
          {r.status !== 'RUNNING' ? (
            <Button
              type="link"
              size="small"
              icon={<PlayCircleOutlined />}
              onClick={() => handleStart(r.id!)}
            >
              启动
            </Button>
          ) : (
            <Button
              type="link"
              size="small"
              icon={<PauseCircleOutlined />}
              onClick={() => handleStop(r.id!)}
            >
              停止
            </Button>
          )}
          <Button
            type="link"
            size="small"
            icon={<EyeOutlined />}
            onClick={() => nav(`/farb/${r.id}`)}
          >
            详情
          </Button>
          <Button
            type="link"
            size="small"
            icon={<EditOutlined />}
            disabled={r.status === 'RUNNING'}
            onClick={() => nav(`/farb/${r.id}/edit`)}
          >
            编辑
          </Button>
          <Popconfirm
            title="确认删除？"
            onConfirm={() => handleDelete(r.id!)}
            disabled={r.status === 'RUNNING'}
          >
            <Button
              type="link"
              danger
              size="small"
              icon={<DeleteOutlined />}
              disabled={r.status === 'RUNNING'}
            >
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <Card style={{ marginBottom: 16 }}>
        <Row gutter={16}>
          <Col span={4}>
            <Statistic title="策略数" value={data.length} />
          </Col>
          <Col span={4}>
            <Statistic
              title="运行中"
              value={runningCount}
              valueStyle={{ color: '#3f8600' }}
            />
          </Col>
          <Col span={4}>
            <Statistic title="总开仓中 pair" value={totalOpenPairs} />
          </Col>
          <Col span={6}>
            <Statistic
              title="累计 Funding PnL"
              value={totalFundingPnl}
              precision={2}
              valueStyle={{ color: totalFundingPnl >= 0 ? '#3f8600' : '#cf1322' }}
              suffix="USDT"
            />
          </Col>
          <Col span={6}>
            <Statistic
              title="累计净 PnL"
              value={totalNetPnl}
              precision={2}
              valueStyle={{ color: totalNetPnl >= 0 ? '#3f8600' : '#cf1322' }}
              suffix="USDT"
            />
          </Col>
        </Row>
      </Card>

      <Card
        title="资金费套利策略"
        extra={
          <Space>
            <Button icon={<ReloadOutlined />} onClick={load}>
              刷新
            </Button>
            <Button
              type="primary"
              icon={<PlusOutlined />}
              onClick={() => nav('/farb/new')}
            >
              新建策略
            </Button>
          </Space>
        }
      >
        <Table
          rowKey="id"
          columns={columns as any}
          dataSource={data}
          loading={loading}
          pagination={{ pageSize: 20 }}
          size="small"
        />
      </Card>
    </div>
  );
};

export default FarbStrategyList;
