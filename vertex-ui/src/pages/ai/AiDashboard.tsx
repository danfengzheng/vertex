import { useEffect, useMemo, useState } from 'react';
import { Card, Table, Tabs, Tag, Space, Button, Select, InputNumber, Alert, Empty, Tooltip, Progress } from 'antd';
import { ReloadOutlined, RobotOutlined, LinkOutlined } from '@ant-design/icons';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { aiApi, AiSignalAnalysisRow, AiTradeAnalysisRow } from '../../api/ai';
import { formatTimestamp } from '../../utils/date';

/**
 * AI 仪表盘：跨信号 / 跨回测查看最近的 AI 分析。
 * <p>
 * 两个 Tab：
 *  - 实时信号：列出最近的 AiSignalAnalysisRow，可按 alignment / suggestedAction 过滤
 *  - 回测 Trade：列出最近的 AiTradeAnalysisRow，可按 verdict 过滤
 * </p>
 */
export const AiDashboard = () => {
  const { t } = useTranslation();
  const [limit, setLimit] = useState(100);

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Space>
          <h2 style={{ margin: 0, fontSize: 20, fontWeight: 600 }}>
            <RobotOutlined /> {t('text.ai.dashboardTitle')}
          </h2>
        </Space>
        <Space>
          <span>{t('text.ai.limit')}</span>
          <InputNumber min={20} max={500} step={20} value={limit} onChange={(v) => setLimit(Number(v) || 100)} />
          <span>{t('text.ai.rows')}</span>
        </Space>
      </div>

      <Card size="small">
        <Tabs
          defaultActiveKey="signals"
          items={[
            {
              key: 'signals',
              label: <span><RobotOutlined /> {t('text.ai.tabSignals')}</span>,
              children: <SignalsTab limit={limit} />,
            },
            {
              key: 'trades',
              label: <span><RobotOutlined /> {t('text.ai.tabTrades')}</span>,
              children: <TradesTab limit={limit} />,
            },
          ]}
        />
      </Card>

      <Alert
        style={{ marginTop: 12 }}
        type="info"
        showIcon
        message={t('text.ai.menuHint')}
      />
    </div>
  );
};

// ─── 实时信号 Tab ─────────────────────────────────────────────────

const SignalsTab = ({ limit }: { limit: number }) => {
  const { t } = useTranslation();
  const [loading, setLoading] = useState(false);
  const [rows, setRows] = useState<AiSignalAnalysisRow[]>([]);
  const [alignmentFilter, setAlignmentFilter] = useState<string | undefined>();
  const [actionFilter, setActionFilter] = useState<string | undefined>();
  const [error, setError] = useState<string | null>(null);

  const fetch = async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await aiApi.listSignalAnalyses(limit);
      setRows(res.data ?? []);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'unknown error');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetch();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [limit]);

  const filtered = useMemo(() => {
    return rows.filter((r) => {
      if (alignmentFilter && r.analysis?.alignment !== alignmentFilter) return false;
      if (actionFilter && r.analysis?.suggestedAction !== actionFilter) return false;
      return true;
    });
  }, [rows, alignmentFilter, actionFilter]);

  const alignmentColor = (a?: string | null) =>
    a === 'ALIGNED' ? 'green' : a === 'DIVERGED' ? 'red' : 'default';

  const columns = [
    {
      title: t('text.ai.analyzedAt'),
      dataIndex: ['analysis', 'analyzedAt'],
      key: 'analyzedAt',
      width: 160,
      render: (v: number | null | undefined) => (v ? formatTimestamp(v) : '-'),
    },
    {
      title: t('text.strategy.name'),
      dataIndex: 'strategyName',
      key: 'strategyName',
      width: 130,
      render: (v: string | null | undefined) => v ?? <span style={{ color: '#999' }}>(deleted)</span>,
    },
    {
      title: t('text.strategy.symbol'),
      key: 'symbol',
      width: 150,
      render: (_: unknown, r: AiSignalAnalysisRow) => (
        <span style={{ fontWeight: 500 }}>
          <span style={{ textTransform: 'uppercase' }}>{r.exchange}</span>{r.exchange ? ' · ' : ''}
          {r.symbol}{r.interval ? ` · ${r.interval}` : ''}
        </span>
      ),
    },
    {
      title: t('text.strategy.signalType'),
      key: 'signalType',
      width: 80,
      render: (_: unknown, r: AiSignalAnalysisRow) =>
        r.signalType
          ? <Tag color={r.signalType === 'BUY' ? 'success' : r.signalType === 'SELL' ? 'error' : 'default'}>{r.signalType}</Tag>
          : '-',
    },
    {
      title: t('text.ai.filterAlignment'),
      key: 'alignment',
      width: 90,
      render: (_: unknown, r: AiSignalAnalysisRow) =>
        r.analysis?.alignment
          ? <Tag color={alignmentColor(r.analysis.alignment)}>
              {t(`text.strategy.aiAlignment.${r.analysis.alignment}`, { defaultValue: r.analysis.alignment })}
            </Tag>
          : '-',
    },
    {
      title: t('text.ai.filterMarketRegime'),
      key: 'regime',
      width: 90,
      render: (_: unknown, r: AiSignalAnalysisRow) =>
        r.analysis?.marketRegime
          ? <Tag color="blue">{t(`text.strategy.aiMarketRegime.${r.analysis.marketRegime}`, { defaultValue: r.analysis.marketRegime })}</Tag>
          : '-',
    },
    {
      title: t('text.ai.filterSuggestedAction'),
      key: 'action',
      width: 130,
      render: (_: unknown, r: AiSignalAnalysisRow) =>
        r.analysis?.suggestedAction
          ? <Tag color="purple">{t(`text.strategy.aiSuggestedAction.${r.analysis.suggestedAction}`, { defaultValue: r.analysis.suggestedAction })}</Tag>
          : '-',
    },
    {
      title: t('text.strategy.aiConfidence'),
      key: 'confidence',
      width: 130,
      render: (_: unknown, r: AiSignalAnalysisRow) => {
        const pct = r.analysis?.confidence != null ? Math.round(r.analysis.confidence * 100) : 0;
        return <Progress percent={pct} size="small" />;
      },
    },
    {
      title: t('text.strategy.aiSummary'),
      key: 'summary',
      render: (_: unknown, r: AiSignalAnalysisRow) =>
        r.analysis?.summary
          ? <Tooltip title={r.analysis.summary}><span>{r.analysis.summary}</span></Tooltip>
          : <span style={{ color: '#999' }}>-</span>,
    },
    {
      title: t('common.operation'),
      key: 'action',
      width: 100,
      render: () => (
        <Link to="/strategy/signals">
          <Button type="link" icon={<LinkOutlined />} size="small">
            {t('text.ai.openSignal')}
          </Button>
        </Link>
      ),
    },
  ];

  if (error) return <Alert type="error" message={error} />;

  return (
    <div>
      <Space wrap style={{ marginBottom: 12 }}>
        <span>{t('text.ai.filterAlignment')}:</span>
        <Select
          allowClear
          placeholder="All"
          style={{ width: 140 }}
          value={alignmentFilter}
          onChange={setAlignmentFilter}
          options={[
            { value: 'ALIGNED', label: t('text.strategy.aiAlignment.ALIGNED') },
            { value: 'NEUTRAL', label: t('text.strategy.aiAlignment.NEUTRAL') },
            { value: 'DIVERGED', label: t('text.strategy.aiAlignment.DIVERGED') },
          ]}
        />
        <span>{t('text.ai.filterSuggestedAction')}:</span>
        <Select
          allowClear
          placeholder="All"
          style={{ width: 180 }}
          value={actionFilter}
          onChange={setActionFilter}
          options={[
            { value: 'ENTER_FULL', label: t('text.strategy.aiSuggestedAction.ENTER_FULL') },
            { value: 'ENTER_HALF', label: t('text.strategy.aiSuggestedAction.ENTER_HALF') },
            { value: 'ENTER_WITH_TIGHT_STOP', label: t('text.strategy.aiSuggestedAction.ENTER_WITH_TIGHT_STOP') },
            { value: 'OBSERVE', label: t('text.strategy.aiSuggestedAction.OBSERVE') },
            { value: 'SKIP', label: t('text.strategy.aiSuggestedAction.SKIP') },
          ]}
        />
        <Button icon={<ReloadOutlined />} onClick={fetch} loading={loading}>
          {t('common.refresh', { defaultValue: 'Refresh' })}
        </Button>
      </Space>

      <Table
        rowKey={(r) => `s-${r.signalId}`}
        size="small"
        loading={loading}
        columns={columns}
        dataSource={filtered}
        scroll={{ x: 'max-content' }}
        pagination={{ pageSize: 20, showSizeChanger: true }}
        locale={{ emptyText: <Empty description={t('text.ai.noData')} /> }}
        expandable={{
          expandedRowRender: (r) => (
            <div style={{ paddingLeft: 24 }}>
              {r.analysis?.summary && (
                <div style={{ marginBottom: 6 }}>
                  <strong>{t('text.strategy.aiSummary')}: </strong>{r.analysis.summary}
                </div>
              )}
              {r.analysis?.keyFactors && r.analysis.keyFactors.length > 0 && (
                <div style={{ marginBottom: 6 }}>
                  <strong>{t('text.strategy.aiKeyFactors')}:</strong>
                  <ul style={{ margin: '2px 0 0 18px', paddingLeft: 0 }}>
                    {r.analysis.keyFactors.map((f, i) => <li key={i}>{f}</li>)}
                  </ul>
                </div>
              )}
              {r.analysis?.risks && r.analysis.risks.length > 0 && (
                <div style={{ marginBottom: 6 }}>
                  <strong style={{ color: '#fa8c16' }}>{t('text.strategy.aiRisks')}:</strong>
                  <ul style={{ margin: '2px 0 0 18px', paddingLeft: 0, color: '#fa8c16' }}>
                    {r.analysis.risks.map((f, i) => <li key={i}>{f}</li>)}
                  </ul>
                </div>
              )}
              {r.analysis?.errorMessage && (
                <Alert type="error" message={r.analysis.errorMessage} style={{ marginTop: 6 }} />
              )}
              <div style={{ marginTop: 6, color: '#999', fontSize: 12 }}>
                {t('text.strategy.aiSummary')} model: {r.analysis?.model ?? '-'}
                {' · '}signalId: {r.signalId}
              </div>
            </div>
          ),
        }}
      />
    </div>
  );
};

// ─── 回测 Trade Tab ───────────────────────────────────────────────

const TradesTab = ({ limit }: { limit: number }) => {
  const { t } = useTranslation();
  const [loading, setLoading] = useState(false);
  const [rows, setRows] = useState<AiTradeAnalysisRow[]>([]);
  const [verdictFilter, setVerdictFilter] = useState<string | undefined>();
  const [error, setError] = useState<string | null>(null);

  const fetch = async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await aiApi.listTradeAnalyses(limit);
      setRows(res.data ?? []);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'unknown error');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetch();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [limit]);

  const filtered = useMemo(() => {
    return rows.filter((r) => {
      if (verdictFilter && r.analysis?.verdict !== verdictFilter) return false;
      return true;
    });
  }, [rows, verdictFilter]);

  const verdictColor: Record<string, string> = {
    GOOD_ENTRY: 'green', LATE_ENTRY: 'orange', FALSE_SIGNAL: 'red',
    GOOD_EXIT: 'green', EARLY_EXIT: 'orange', BAD_STOP_LOSS: 'red',
    LUCKY_PROFIT: 'gold',
  };

  const columns = [
    {
      title: t('text.ai.analyzedAt'),
      dataIndex: ['analysis', 'analyzedAt'],
      key: 'analyzedAt',
      width: 160,
      render: (v: number | null | undefined) => (v ? formatTimestamp(v) : '-'),
    },
    {
      title: t('text.strategy.name'),
      dataIndex: 'strategyName',
      key: 'strategyName',
      width: 130,
      render: (v: string | null | undefined) => v ?? <span style={{ color: '#999' }}>(deleted)</span>,
    },
    {
      title: t('text.strategy.symbol'),
      key: 'symbol',
      width: 150,
      render: (_: unknown, r: AiTradeAnalysisRow) => (
        <span style={{ fontWeight: 500 }}>
          <span style={{ textTransform: 'uppercase' }}>{r.exchange}</span>{r.exchange ? ' · ' : ''}
          {r.symbol}{r.interval ? ` · ${r.interval}` : ''}
        </span>
      ),
    },
    {
      title: t('text.strategy.direction'),
      key: 'type',
      width: 70,
      render: (_: unknown, r: AiTradeAnalysisRow) =>
        r.type ? <Tag color={r.type === 'LONG' ? 'success' : 'blue'}>{r.type}</Tag> : '-',
    },
    {
      title: t('text.strategy.entryTime'),
      key: 'entryTime',
      width: 140,
      render: (_: unknown, r: AiTradeAnalysisRow) =>
        r.entryTime ? formatTimestamp(r.entryTime, 'YYYY-MM-DD HH:mm') : '-',
    },
    {
      title: '损益%',
      dataIndex: 'profitPercent',
      key: 'profitPercent',
      width: 80,
      render: (v?: string | null) => {
        if (v == null) return '-';
        const n = Number(v);
        return <span style={{ color: n >= 0 ? '#3f8600' : '#cf1322' }}>{n.toFixed(2)}%</span>;
      },
    },
    {
      title: t('text.ai.filterVerdict'),
      key: 'verdict',
      width: 110,
      render: (_: unknown, r: AiTradeAnalysisRow) =>
        r.analysis?.verdict
          ? <Tag color={verdictColor[r.analysis.verdict] || 'default'}>
              {t(`text.strategy.aiVerdict.${r.analysis.verdict}`, { defaultValue: r.analysis.verdict })}
            </Tag>
          : '-',
    },
    {
      title: 'Quality',
      key: 'quality',
      width: 110,
      render: (_: unknown, r: AiTradeAnalysisRow) => {
        const pct = r.analysis?.quality != null ? Math.round(r.analysis.quality * 100) : 0;
        return <Progress percent={pct} size="small" />;
      },
    },
    {
      title: t('text.strategy.aiSummary'),
      key: 'summary',
      render: (_: unknown, r: AiTradeAnalysisRow) =>
        r.analysis?.summary
          ? <Tooltip title={r.analysis.summary}><span>{r.analysis.summary}</span></Tooltip>
          : <span style={{ color: '#999' }}>-</span>,
    },
  ];

  if (error) return <Alert type="error" message={error} />;

  return (
    <div>
      <Space wrap style={{ marginBottom: 12 }}>
        <span>{t('text.ai.filterVerdict')}:</span>
        <Select
          allowClear
          placeholder="All"
          style={{ width: 180 }}
          value={verdictFilter}
          onChange={setVerdictFilter}
          options={[
            { value: 'GOOD_ENTRY', label: t('text.strategy.aiVerdict.GOOD_ENTRY') },
            { value: 'LATE_ENTRY', label: t('text.strategy.aiVerdict.LATE_ENTRY') },
            { value: 'FALSE_SIGNAL', label: t('text.strategy.aiVerdict.FALSE_SIGNAL') },
            { value: 'GOOD_EXIT', label: t('text.strategy.aiVerdict.GOOD_EXIT') },
            { value: 'EARLY_EXIT', label: t('text.strategy.aiVerdict.EARLY_EXIT') },
            { value: 'BAD_STOP_LOSS', label: t('text.strategy.aiVerdict.BAD_STOP_LOSS') },
            { value: 'LUCKY_PROFIT', label: t('text.strategy.aiVerdict.LUCKY_PROFIT') },
          ]}
        />
        <Button icon={<ReloadOutlined />} onClick={fetch} loading={loading}>
          {t('common.refresh', { defaultValue: 'Refresh' })}
        </Button>
      </Space>

      <Table
        rowKey={(r) => `t-${r.cacheKey}-${r.tradeIndex}`}
        size="small"
        loading={loading}
        columns={columns}
        dataSource={filtered}
        scroll={{ x: 'max-content' }}
        pagination={{ pageSize: 20, showSizeChanger: true }}
        locale={{ emptyText: <Empty description={t('text.ai.noData')} /> }}
        expandable={{
          expandedRowRender: (r) => (
            <div style={{ paddingLeft: 24 }}>
              {r.analysis?.summary && (
                <div style={{ marginBottom: 6 }}>
                  <strong>{t('text.strategy.aiSummary')}: </strong>{r.analysis.summary}
                </div>
              )}
              {r.analysis?.entryFactors && r.analysis.entryFactors.length > 0 && (
                <div style={{ marginBottom: 6 }}>
                  <strong>{t('text.strategy.tradeAiEntry')}:</strong>
                  <ul style={{ margin: '2px 0 0 18px', paddingLeft: 0 }}>
                    {r.analysis.entryFactors.map((f, i) => <li key={i}>{f}</li>)}
                  </ul>
                </div>
              )}
              {r.analysis?.exitFactors && r.analysis.exitFactors.length > 0 && (
                <div style={{ marginBottom: 6 }}>
                  <strong>{t('text.strategy.tradeAiExit')}:</strong>
                  <ul style={{ margin: '2px 0 0 18px', paddingLeft: 0 }}>
                    {r.analysis.exitFactors.map((f, i) => <li key={i}>{f}</li>)}
                  </ul>
                </div>
              )}
              {r.analysis?.improvements && r.analysis.improvements.length > 0 && (
                <div style={{ marginBottom: 6 }}>
                  <strong style={{ color: '#1677ff' }}>{t('text.strategy.tradeAiSuggestions')}:</strong>
                  <ul style={{ margin: '2px 0 0 18px', paddingLeft: 0, color: '#1677ff' }}>
                    {r.analysis.improvements.map((f, i) => <li key={i}>{f}</li>)}
                  </ul>
                </div>
              )}
              {r.analysis?.errorMessage && (
                <Alert type="error" message={r.analysis.errorMessage} style={{ marginTop: 6 }} />
              )}
              <div style={{ marginTop: 6, color: '#999', fontSize: 12 }}>
                model: {r.analysis?.model ?? '-'}
                {' · '}cacheKey: {r.cacheKey.slice(0, 16)}…
                {' · '}tradeIndex: {r.tradeIndex}
              </div>
            </div>
          ),
        }}
      />
    </div>
  );
};
