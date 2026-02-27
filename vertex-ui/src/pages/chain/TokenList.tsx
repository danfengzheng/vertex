import { useState, useCallback, useEffect } from 'react';
import {
  Table, Button, Space, Tag, Select, Input, InputNumber,
  message, Progress, Tooltip, Descriptions, Badge,
  Row, Col, Statistic, Drawer,
} from 'antd';
import {
  ReloadOutlined, SearchOutlined, ThunderboltOutlined,
  RiseOutlined, FallOutlined, LinkOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import {
  chainTokenApi,
  ChainTokenVO,
  ChainTokenDetailVO,
  ChainTokenQueryDTO,
  ChainCode,
  TokenStatus,
} from '../../api/chain';
import { formatTimestamp } from '../../utils/date';
import type { TablePaginationConfig } from 'antd';

// ─── 评级颜色映射 ──────────────────────────────────

const SCORE_COLOR = (score: number) => {
  if (score >= 80) return '#52c41a';  // S - 绿
  if (score >= 65) return '#1677ff';  // A - 蓝
  if (score >= 50) return '#faad14';  // B - 黄
  if (score >= 35) return '#ff7a45';  // C - 橙
  return '#ff4d4f';                   // D - 红
};

const SCORE_GRADE = (score: number) => {
  if (score >= 80) return 'S';
  if (score >= 65) return 'A';
  if (score >= 50) return 'B';
  if (score >= 35) return 'C';
  return 'D';
};

const STATUS_COLOR: Record<TokenStatus, string> = {
  PENDING: 'default',
  SCORED: 'processing',
  ALERTED: 'success',
  IGNORED: 'error',
};

// ─── 工具方法 ──────────────────────────────────────

const fmtUsd = (val: string | number | null | undefined): string => {
  if (val == null) return '-';
  const n = Number(val);
  if (isNaN(n)) return '-';
  if (n >= 1_000_000) return `$${(n / 1_000_000).toFixed(2)}M`;
  if (n >= 1_000) return `$${(n / 1_000).toFixed(1)}K`;
  return `$${n.toFixed(4)}`;
};

const fmtPct = (val: string | number | null | undefined): string => {
  if (val == null) return '-';
  const n = Number(val);
  return isNaN(n) ? '-' : `${n >= 0 ? '+' : ''}${n.toFixed(2)}%`;
};

// ─── 主组件 ──────────────────────────────────────

export const TokenList = () => {
  const { t } = useTranslation();
  const [loading, setLoading] = useState(false);
  const [scanning, setScanning] = useState(false);
  const [tokens, setTokens] = useState<ChainTokenVO[]>([]);
  const [total, setTotal] = useState(0);
  const [query, setQuery] = useState<ChainTokenQueryDTO>({ pageNum: 1, pageSize: 20 });
  const [detailVisible, setDetailVisible] = useState(false);
  const [detailData, setDetailData] = useState<ChainTokenDetailVO | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);

  // ─── 筛选状态 ────────────────────────────────────
  const [filterChain, setFilterChain] = useState<ChainCode | undefined>();
  const [filterSymbol, setFilterSymbol] = useState('');
  const [filterMinScore, setFilterMinScore] = useState<number | null>(null);
  const [filterStatus, setFilterStatus] = useState<TokenStatus | undefined>();

  const fetchTokens = useCallback(async () => {
    setLoading(true);
    try {
      const res = await chainTokenApi.page(query);
      setTokens(res.data?.records || []);
      setTotal(res.data?.total || 0);
    } catch {
      // handled by interceptor
    } finally {
      setLoading(false);
    }
  }, [query]);

  useEffect(() => { fetchTokens(); }, [fetchTokens]);

  const handleSearch = () => {
    setQuery({
      pageNum: 1,
      pageSize: query.pageSize,
      chain: filterChain,
      symbol: filterSymbol || undefined,
      minScore: filterMinScore ?? undefined,
      status: filterStatus,
    });
  };

  const handleReset = () => {
    setFilterChain(undefined);
    setFilterSymbol('');
    setFilterMinScore(null);
    setFilterStatus(undefined);
    setQuery({ pageNum: 1, pageSize: 20 });
  };

  const handleScan = async () => {
    setScanning(true);
    try {
      await chainTokenApi.scan('ALL');
      message.success(t('message.chain.scanSubmitted'));
      setTimeout(() => fetchTokens(), 3000);
    } catch {
      // handled
    } finally {
      setScanning(false);
    }
  };

  const handleDetail = async (id: string) => {
    setDetailVisible(true);
    setDetailLoading(true);
    try {
      const res = await chainTokenApi.getById(id);
      setDetailData(res.data || null);
    } catch {
      // handled
    } finally {
      setDetailLoading(false);
    }
  };

  const handleTableChange = (pagination: TablePaginationConfig) => {
    setQuery(q => ({ ...q, pageNum: pagination.current || 1, pageSize: pagination.pageSize || 20 }));
  };

  // ─── 表格列 ──────────────────────────────────────
  const columns = [
    {
      title: t('text.chain.chain'),
      dataIndex: 'chain',
      key: 'chain',
      width: 70,
      render: (v: string) => (
        <Tag color={v === 'BNB' ? 'gold' : 'purple'}>{v}</Tag>
      ),
    },
    {
      title: t('text.chain.symbol'),
      dataIndex: 'symbol',
      key: 'symbol',
      width: 100,
      render: (v: string, record: ChainTokenVO) => (
        <Space direction="vertical" size={0}>
          <span style={{ fontWeight: 600 }}>{v}</span>
          <span style={{ fontSize: 12, color: '#8c8c8c' }}>{record.name}</span>
        </Space>
      ),
    },
    {
      title: t('text.chain.score'),
      dataIndex: 'score',
      key: 'score',
      width: 100,
      sorter: true,
      render: (score: number) => (
        <Tooltip title={`${t('text.chain.scoreOnchain')}: ${score}`}>
          <Space>
            <span style={{ fontWeight: 700, color: SCORE_COLOR(score), fontSize: 18 }}>
              {SCORE_GRADE(score)}
            </span>
            <Progress
              type="circle"
              percent={score}
              size={36}
              strokeColor={SCORE_COLOR(score)}
              format={() => score}
            />
          </Space>
        </Tooltip>
      ),
    },
    {
      title: t('text.chain.subScores'),
      key: 'subScores',
      width: 160,
      render: (_: unknown, record: ChainTokenVO) => (
        <Space direction="vertical" size={0} style={{ fontSize: 11 }}>
          <span>链上 <Tag color="blue">{record.scoreOnchain}/30</Tag></span>
          <span>市场 <Tag color="green">{record.scoreMarket}/40</Tag></span>
          <span>经济 <Tag color="orange">{record.scoreTokenomics}/20</Tag></span>
          <span>新颖 <Tag color="purple">{record.scoreNovelty}/10</Tag></span>
        </Space>
      ),
    },
    {
      title: t('text.chain.priceUsd'),
      dataIndex: 'priceUsd',
      key: 'priceUsd',
      width: 100,
      render: (v: string | null) => fmtUsd(v),
    },
    {
      title: t('text.chain.liquidityUsd'),
      dataIndex: 'liquidityUsd',
      key: 'liquidityUsd',
      width: 100,
      render: (v: string | null) => fmtUsd(v),
    },
    {
      title: t('text.chain.holderCount'),
      dataIndex: 'holderCount',
      key: 'holderCount',
      width: 80,
      render: (v: number | null) => v != null ? v.toLocaleString() : '-',
    },
    {
      title: t('common.status'),
      dataIndex: 'status',
      key: 'status',
      width: 90,
      render: (v: TokenStatus, record: ChainTokenVO) => (
        <Space>
          <Badge status={v === 'ALERTED' ? 'success' : 'processing'} />
          <Tag color={STATUS_COLOR[v]}>{v}</Tag>
          {record.alerted === 1 && <ThunderboltOutlined style={{ color: '#faad14' }} />}
        </Space>
      ),
    },
    {
      title: t('text.chain.deployTime'),
      dataIndex: 'deployTime',
      key: 'deployTime',
      width: 150,
      render: (v: number) => v ? formatTimestamp(v) : '-',
    },
    {
      title: t('common.operation'),
      key: 'action',
      width: 100,
      fixed: 'right' as const,
      render: (_: unknown, record: ChainTokenVO) => (
        <Button type="link" size="small" onClick={() => handleDetail(record.id)}>
          {t('common.edit')} / 详情
        </Button>
      ),
    },
  ];

  return (
    <div>
      {/* 筛选栏 */}
      <Row gutter={[12, 12]} style={{ marginBottom: 16 }}>
        <Col>
          <Select
            placeholder={t('text.chain.chain')}
            style={{ width: 100 }}
            allowClear
            value={filterChain}
            onChange={v => setFilterChain(v)}
            options={[
              { label: 'BNB', value: 'BNB' },
              { label: 'Solana', value: 'SOL' },
            ]}
          />
        </Col>
        <Col>
          <Input
            placeholder={t('text.chain.symbol')}
            style={{ width: 120 }}
            value={filterSymbol}
            onChange={e => setFilterSymbol(e.target.value)}
            onPressEnter={handleSearch}
          />
        </Col>
        <Col>
          <InputNumber
            placeholder={t('text.chain.minScore')}
            style={{ width: 120 }}
            min={0} max={100}
            value={filterMinScore}
            onChange={v => setFilterMinScore(v)}
          />
        </Col>
        <Col>
          <Select
            placeholder={t('common.status')}
            style={{ width: 110 }}
            allowClear
            value={filterStatus}
            onChange={v => setFilterStatus(v)}
            options={[
              { label: 'PENDING', value: 'PENDING' },
              { label: 'SCORED', value: 'SCORED' },
              { label: 'ALERTED', value: 'ALERTED' },
              { label: 'IGNORED', value: 'IGNORED' },
            ]}
          />
        </Col>
        <Col>
          <Space>
            <Button icon={<SearchOutlined />} type="primary" onClick={handleSearch}>
              {t('common.search')}
            </Button>
            <Button onClick={handleReset}>{t('common.reset')}</Button>
            <Button icon={<ReloadOutlined />} onClick={fetchTokens} loading={loading}>
              {t('text.quote.refresh')}
            </Button>
            <Button
              icon={<ThunderboltOutlined />}
              onClick={handleScan}
              loading={scanning}
              type="dashed"
            >
              {t('text.chain.triggerScan')}
            </Button>
          </Space>
        </Col>
      </Row>

      {/* 数据表格 */}
      <Table
        rowKey="id"
        loading={loading}
        dataSource={tokens}
        columns={columns}
        scroll={{ x: 1100 }}
        pagination={{
          current: query.pageNum,
          pageSize: query.pageSize,
          total,
          showSizeChanger: true,
          showTotal: (t_) => `共 ${t_} 条`,
        }}
        onChange={handleTableChange}
        size="middle"
      />

      {/* 详情抽屉 */}
      <Drawer
        title={`${detailData?.symbol || ''} 代币详情`}
        open={detailVisible}
        onClose={() => setDetailVisible(false)}
        width={640}
        loading={detailLoading}
      >
        {detailData && <TokenDetail data={detailData} />}
      </Drawer>
    </div>
  );
};

// ─── 详情子组件 ──────────────────────────────────────

const TokenDetail = ({ data }: { data: ChainTokenDetailVO }) => {
  const pct1h = data.priceChange1hPct ? Number(data.priceChange1hPct) : null;

  return (
    <Space direction="vertical" style={{ width: '100%' }} size="large">
      {/* 评分摘要 */}
      <Row gutter={16}>
        <Col span={6}>
          <Statistic
            title="综合评分"
            value={data.score}
            suffix={`/ 100 (${SCORE_GRADE(data.score)})`}
            valueStyle={{ color: SCORE_COLOR(data.score) }}
          />
        </Col>
        <Col span={6}><Statistic title="链上" value={data.scoreOnchain} suffix="/30" /></Col>
        <Col span={6}><Statistic title="市场" value={data.scoreMarket} suffix="/40" /></Col>
        <Col span={6}><Statistic title="代币经济" value={data.scoreTokenomics} suffix="/20" /></Col>
      </Row>

      {/* 基础信息 */}
      <Descriptions title="基础信息" bordered size="small" column={2}>
        <Descriptions.Item label="链">{data.chain}</Descriptions.Item>
        <Descriptions.Item label="符号">{data.symbol}</Descriptions.Item>
        <Descriptions.Item label="名称" span={2}>{data.name || '-'}</Descriptions.Item>
        <Descriptions.Item label="合约地址" span={2}>
          <Space>
            <span style={{ fontSize: 12, fontFamily: 'monospace' }}>
              {data.contractAddress}
            </span>
            <a
              href={data.chain === 'BNB'
                ? `https://bscscan.com/token/${data.contractAddress}`
                : `https://solscan.io/token/${data.contractAddress}`
              }
              target="_blank" rel="noreferrer"
            >
              <LinkOutlined />
            </a>
          </Space>
        </Descriptions.Item>
        <Descriptions.Item label="部署者" span={2}>
          <span style={{ fontSize: 12, fontFamily: 'monospace' }}>
            {data.deployerAddress || '-'}
          </span>
        </Descriptions.Item>
        <Descriptions.Item label="上线时间">
          {data.deployTime ? formatTimestamp(data.deployTime) : '-'}
        </Descriptions.Item>
        <Descriptions.Item label="存在时长">
          {data.ageMinutes != null ? `${data.ageMinutes} 分钟` : '-'}
        </Descriptions.Item>
        <Descriptions.Item label="精度">{data.decimals ?? '-'}</Descriptions.Item>
        <Descriptions.Item label="合约已验证">
          <Tag color={data.contractVerified === 1 ? 'green' : 'red'}>
            {data.contractVerified === 1 ? '是' : '否'}
          </Tag>
        </Descriptions.Item>
      </Descriptions>

      {/* 市场指标 */}
      <Descriptions title="市场指标" bordered size="small" column={2}>
        <Descriptions.Item label="价格">{fmtUsd(data.priceUsd)}</Descriptions.Item>
        <Descriptions.Item label="市值">{fmtUsd(data.marketCapUsd)}</Descriptions.Item>
        <Descriptions.Item label="流动性">{fmtUsd(data.liquidityUsd)}</Descriptions.Item>
        <Descriptions.Item label="流动性锁定">
          <Tag color={data.liquidityLocked === 1 ? 'green' : 'default'}>
            {data.liquidityLocked === 1 ? '已锁定' : '未锁定'}
          </Tag>
        </Descriptions.Item>
        <Descriptions.Item label="24h 成交量">{fmtUsd(data.volume24hUsd)}</Descriptions.Item>
        <Descriptions.Item label="1h 交易笔数">{data.txCount1h ?? '-'}</Descriptions.Item>
        <Descriptions.Item label="1h 价格变化">
          {pct1h != null ? (
            <span style={{ color: pct1h >= 0 ? '#52c41a' : '#ff4d4f' }}>
              {pct1h >= 0 ? <RiseOutlined /> : <FallOutlined />} {fmtPct(pct1h)}
            </span>
          ) : '-'}
        </Descriptions.Item>
        <Descriptions.Item label="买盘压力">
          {data.buyPressure1h != null ? `${Number(data.buyPressure1h).toFixed(1)}%` : '-'}
        </Descriptions.Item>
      </Descriptions>

      {/* 代币经济 */}
      <Descriptions title="代币经济" bordered size="small" column={2}>
        <Descriptions.Item label="持有者数">{data.holderCount?.toLocaleString() ?? '-'}</Descriptions.Item>
        <Descriptions.Item label="Top10 集中度">
          {data.top10HolderPct != null ? `${Number(data.top10HolderPct).toFixed(1)}%` : '-'}
        </Descriptions.Item>
        <Descriptions.Item label="部署者持仓">
          {data.deployerHoldingPct != null ? `${Number(data.deployerHoldingPct).toFixed(2)}%` : '-'}
        </Descriptions.Item>
        <Descriptions.Item label="LP 池占比">
          {data.lpPoolPct != null ? `${Number(data.lpPoolPct).toFixed(1)}%` : '-'}
        </Descriptions.Item>
        <Descriptions.Item label="Pump.fun 上市">
          <Tag color={data.pumpFunListed === 1 ? 'purple' : 'default'}>
            {data.pumpFunListed === 1 ? '是' : '否'}
          </Tag>
        </Descriptions.Item>
        <Descriptions.Item label="LP 添加次数">{data.lpAddCount ?? '-'}</Descriptions.Item>
      </Descriptions>
    </Space>
  );
};
