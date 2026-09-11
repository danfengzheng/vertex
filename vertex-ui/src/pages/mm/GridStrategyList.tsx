import { useEffect, useState } from 'react';
import { Table, Button, Space, Tag, Modal, message, Popconfirm, Card, Row, Col, Statistic } from 'antd';
import {
  PlayCircleOutlined, PauseCircleOutlined, ThunderboltOutlined,
  PlusOutlined, EditOutlined, DeleteOutlined, EyeOutlined, ReloadOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { mmGridApi, GridStrategyVO, GridStatus, ProtectionLayer } from '../../api/mmGrid';

const STATUS_COLOR: Record<GridStatus, string> = {
  STOPPED: 'default',
  RUNNING: 'success',
  PAUSED: 'warning',
  EMERGENCY_STOPPED: 'error',
};

const LAYER_COLOR: Record<ProtectionLayer, string> = {
  NORMAL: 'success',
  LAYER1: 'processing',
  LAYER2: 'warning',
  LAYER3: 'orange',
  LAYER4: 'error',
  EMERGENCY: 'error',
};

/**
 * 做市 - 网格策略列表页
 */
export const GridStrategyList = () => {
  const { t } = useTranslation();
  const nav = useNavigate();
  const [data, setData] = useState<GridStrategyVO[]>([]);
  const [loading, setLoading] = useState(false);

  const load = async () => {
    setLoading(true);
    try {
      const res = await mmGridApi.list();
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

  const runningCount = data.filter(s => s.status === 'RUNNING').length;
  const emergencyCount = data.filter(s => s.status === 'EMERGENCY_STOPPED').length;
  const totalArbPnl = data.reduce((sum, s) => sum + Number(s.totalArbitragePnl ?? 0), 0);
  const totalDirPnl = data.reduce((sum, s) => sum + Number(s.totalDirectionalPnl ?? 0), 0);

  const handleStart = async (id: string) => {
    try {
      await mmGridApi.start(id);
      message.success(t('mm.startOk', { defaultValue: '策略已启动' }));
      load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : 'start failed');
    }
  };

  const handleStop = async (id: string) => {
    try {
      await mmGridApi.stop(id);
      message.success(t('mm.stopOk', { defaultValue: '策略已停止' }));
      load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : 'stop failed');
    }
  };

  const handleEmergency = (record: GridStrategyVO) => {
    Modal.confirm({
      title: t('mm.emergencyTitle', { defaultValue: '紧急停机' }),
      icon: <ThunderboltOutlined style={{ color: '#cf1322' }} />,
      content: (
        <div>
          <p>{t('mm.emergencyContent', { defaultValue: '选择紧急停机模式：' })}</p>
          <ul style={{ paddingLeft: 20 }}>
            <li>SOFT_STOP: 撤所有挂单，保留持仓</li>
            <li>GRACEFUL_CLOSE: 撤挂单 + GTX 追单平仓（推荐）</li>
            <li>LIMIT_LADDER_CLOSE: 分档 GTX 平仓</li>
            <li>MARKET_FALLBACK: 破例 taker 强平（收手续费！）</li>
          </ul>
        </div>
      ),
      okText: 'GRACEFUL_CLOSE',
      okType: 'danger',
      cancelText: '取消',
      onOk: async () => {
        try {
          await mmGridApi.emergency(record.id!, 'GRACEFUL_CLOSE', 'manual from UI');
          message.warning('紧急停机已触发');
          load();
        } catch (e) {
          message.error(e instanceof Error ? e.message : 'emergency failed');
        }
      },
    });
  };

  const handleRecover = (record: GridStrategyVO) => {
    Modal.confirm({
      title: '从紧急停机恢复',
      icon: <ThunderboltOutlined style={{ color: '#faad14' }} />,
      content: (
        <div>
          <p>确认已排查以下问题：</p>
          <ul style={{ paddingLeft: 20 }}>
            <li>持仓和挂单已人工核对（币安 UI 和 grid_order 表）</li>
            <li>告警根因已定位（查看 grid_event 表 EMERGENCY_STOP payload）</li>
            <li>参数已调整（如需要）</li>
          </ul>
          <p style={{ color: '#cf1322' }}>恢复后策略变为 STOPPED，可以重新启动。</p>
        </div>
      ),
      okText: '确认恢复',
      okType: 'primary',
      cancelText: '取消',
      onOk: async () => {
        try {
          await mmGridApi.recover(record.id!, 'manual from UI');
          message.success('已恢复为 STOPPED，可以重新启动');
          load();
        } catch (e) {
          message.error(e instanceof Error ? e.message : 'recover failed');
        }
      },
    });
  };

  const handleDelete = async (id: string) => {
    try {
      await mmGridApi.delete(id);
      message.success('已删除');
      load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : 'delete failed');
    }
  };

  const columns = [
    { title: t('mm.colName', { defaultValue: '名称' }), dataIndex: 'name', key: 'name',
      render: (name: string, r: GridStrategyVO) => (
        <Space>
          <a onClick={() => nav(`/mm/grid/${r.id}`)}>{name}</a>
          {r.executionMode === 'PAPER' && <Tag color="purple">PAPER</Tag>}
          {r.executionMode === 'LIVE' && <Tag color="magenta">LIVE</Tag>}
        </Space>
      ) },
    { title: t('mm.colSymbol', { defaultValue: '交易对' }), dataIndex: 'symbol', key: 'symbol' },
    { title: t('mm.colStatus', { defaultValue: '状态' }), dataIndex: 'status', key: 'status',
      render: (s: GridStatus) => <Tag color={STATUS_COLOR[s] || 'default'}>{s}</Tag> },
    { title: t('mm.colLayer', { defaultValue: '保护层' }), dataIndex: 'currentActiveLayer', key: 'layer',
      render: (l: ProtectionLayer | undefined) => l
        ? <Tag color={LAYER_COLOR[l] || 'default'}>{l}</Tag>
        : '-' },
    { title: t('mm.colHedge', { defaultValue: 'Hedge' }), dataIndex: 'hedgeMode', key: 'hedge',
      render: (v: number) => v === 1 ? <Tag color="blue">双向</Tag> : <Tag>单向</Tag> },
    { title: t('mm.colLeverage', { defaultValue: '杠杆' }), dataIndex: 'leverage', key: 'leverage',
      render: (v: number) => `${v}x` },
    { title: t('mm.colWindow', { defaultValue: '窗口' }), key: 'window',
      render: (_: unknown, r: GridStrategyVO) => `${r.windowSizePerSide} × ${r.qtyPerGrid}` },
    { title: t('mm.colStep', { defaultValue: '步长' }), dataIndex: 'stepPct', key: 'step',
      render: (v: number | null | undefined, r: GridStrategyVO) =>
        v != null ? `${Number(v).toFixed(3)}%` : `${Number(r.baseStepPct).toFixed(3)}% (base)` },
    { title: 'ArbP&L', dataIndex: 'totalArbitragePnl', key: 'arbPnl',
      render: (v: number | undefined) => v == null ? '-' :
        <span style={{ color: Number(v) >= 0 ? '#3f8600' : '#cf1322' }}>{Number(v).toFixed(2)}</span> },
    { title: 'DirP&L', dataIndex: 'totalDirectionalPnl', key: 'dirPnl',
      render: (v: number | undefined) => v == null ? '-' :
        <span style={{ color: Number(v) >= 0 ? '#3f8600' : '#cf1322' }}>{Number(v).toFixed(2)}</span> },
    {
      title: t('common.operation', { defaultValue: '操作' }),
      key: 'action', width: 320, fixed: 'right' as const,
      render: (_: unknown, r: GridStrategyVO) => (
        <Space>
          <Button size="small" icon={<EyeOutlined />} onClick={() => nav(`/mm/grid/${r.id}`)}>
            详情
          </Button>
          {r.status === 'RUNNING' ? (
            <Popconfirm title="停止策略？(SOFT: 撤挂单保留仓位)" onConfirm={() => handleStop(r.id!)}>
              <Button size="small" icon={<PauseCircleOutlined />}>停止</Button>
            </Popconfirm>
          ) : r.status === 'EMERGENCY_STOPPED' ? (
            <Button size="small" icon={<ThunderboltOutlined />}
                    style={{ color: '#faad14', borderColor: '#faad14' }}
                    onClick={() => handleRecover(r)}>
              恢复
            </Button>
          ) : (
            <Button size="small" type="primary" icon={<PlayCircleOutlined />}
                    onClick={() => handleStart(r.id!)}>
              启动
            </Button>
          )}
          {r.status === 'RUNNING' && (
            <Button size="small" danger icon={<ThunderboltOutlined />} onClick={() => handleEmergency(r)}>
              紧急
            </Button>
          )}
          <Button size="small" icon={<EditOutlined />} onClick={() => nav(`/mm/grid/${r.id}/edit`)}>
            编辑
          </Button>
          <Popconfirm title="删除策略？（须先停止）" onConfirm={() => handleDelete(r.id!)}>
            <Button size="small" danger icon={<DeleteOutlined />} />
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between' }}>
        <h2 style={{ margin: 0 }}>{t('mm.gridListTitle', { defaultValue: '网格策略' })}</h2>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => nav('/mm/grid/new')}>
            新建策略
          </Button>
        </Space>
      </div>

      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col xs={12} md={6}>
          <Card><Statistic title="策略总数" value={data.length} /></Card>
        </Col>
        <Col xs={12} md={6}>
          <Card><Statistic title="运行中" value={runningCount} valueStyle={{ color: '#3f8600' }} /></Card>
        </Col>
        <Col xs={12} md={6}>
          <Card><Statistic title="紧急停机" value={emergencyCount}
                            valueStyle={{ color: emergencyCount > 0 ? '#cf1322' : undefined }} /></Card>
        </Col>
        <Col xs={12} md={6}>
          <Card>
            <Statistic title="累计 Arb P&L" value={totalArbPnl} precision={2}
                       valueStyle={{ color: totalArbPnl >= 0 ? '#3f8600' : '#cf1322' }} />
            <div style={{ fontSize: 11, color: '#888' }}>Dir P&L: {totalDirPnl.toFixed(2)}</div>
          </Card>
        </Col>
      </Row>

      <Card>
        <Table rowKey="id" loading={loading} dataSource={data} columns={columns}
               pagination={false} scroll={{ x: 1400 }} />
      </Card>
    </div>
  );
};
