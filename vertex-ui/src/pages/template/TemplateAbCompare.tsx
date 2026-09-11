import { useEffect, useState } from 'react';
import { Card, Select, Button, Space, Row, Col, DatePicker, InputNumber, message, Table, Tag, Statistic, Empty, Alert, Input } from 'antd';
import { SwapOutlined, PlusOutlined, DeleteOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import dayjs, { Dayjs } from 'dayjs';
import { templateApi, StrategyTemplateVO, AbCompareCandidate, TemplateBuildDTO } from '../../api/template';

const { RangePicker } = DatePicker;

interface CandidateInput {
  templateId?: string;
  paramsJson: string;   // 用户手改 JSON（简单直接，避免动态表单复杂化）
}

/**
 * 策略模板 A/B 对比页 —— 2-4 个候选（模板 + userParams JSON）→ 同一段回测 →
 * 冠军高亮。
 * <p>
 * 为保持简洁，参数以 JSON 文本输入；专业用户复制粘贴即可。
 * </p>
 */
export const TemplateAbCompare = () => {
  const { t } = useTranslation();
  const [templates, setTemplates] = useState<StrategyTemplateVO[]>([]);
  const [candidates, setCandidates] = useState<CandidateInput[]>([
    { paramsJson: '{}' }, { paramsJson: '{}' },
  ]);
  const [range, setRange] = useState<[Dayjs, Dayjs]>([dayjs().subtract(30, 'day'), dayjs()]);
  const [initialCapital, setInitialCapital] = useState(10000);
  const [running, setRunning] = useState(false);
  const [result, setResult] = useState<{ candidates: AbCompareCandidate[]; winnerIndex: number | null } | null>(null);

  useEffect(() => {
    templateApi.list({ status: 'PUBLISHED' }).then(r => {
      if (r.code === 200 && r.data) setTemplates(r.data);
    });
  }, []);

  const addCandidate = () => {
    if (candidates.length >= 4) {
      message.warning('最多 4 个候选');
      return;
    }
    setCandidates([...candidates, { paramsJson: '{}' }]);
  };
  const removeCandidate = (i: number) => {
    if (candidates.length <= 2) return;
    setCandidates(candidates.filter((_, idx) => idx !== i));
  };
  const updateCandidate = (i: number, patch: Partial<CandidateInput>) => {
    setCandidates(candidates.map((c, idx) => idx === i ? { ...c, ...patch } : c));
  };

  const onFillDefaults = (i: number) => {
    const c = candidates[i];
    if (!c.templateId) return;
    const tpl = templates.find(t => t.id === c.templateId);
    if (!tpl) return;
    const defaults: Record<string, unknown> = {};
    (tpl.exposedParams ?? []).forEach(p => {
      if (p.default !== undefined) defaults[p.userKey] = p.default;
    });
    updateCandidate(i, { paramsJson: JSON.stringify(defaults, null, 2) });
  };

  const onRun = async () => {
    // 校验所有候选
    const built: TemplateBuildDTO[] = [];
    for (let i = 0; i < candidates.length; i++) {
      const c = candidates[i];
      if (!c.templateId) {
        message.error(`候选 ${i + 1} 未选择模板`);
        return;
      }
      let userParams: Record<string, unknown>;
      try {
        userParams = JSON.parse(c.paramsJson || '{}');
      } catch (e) {
        message.error(`候选 ${i + 1} 参数 JSON 非法：${e instanceof Error ? e.message : ''}`);
        return;
      }
      built.push({ templateId: c.templateId, userParams });
    }

    setRunning(true);
    setResult(null);
    try {
      const res = await templateApi.abCompare({
        candidates: built,
        startTime: range[0].valueOf(),
        endTime: range[1].valueOf(),
        initialCapital,
      });
      if (res.code === 200 && res.data) {
        setResult({ candidates: res.data.candidates, winnerIndex: res.data.winnerIndex });
      } else {
        message.error(res.message || 'ab-compare failed');
      }
    } catch (e) {
      message.error(e instanceof Error ? e.message : 'failed');
    } finally {
      setRunning(false);
    }
  };

  const fmt = (v: unknown, suffix = '') =>
    v == null ? '-' : (typeof v === 'number' ? v.toFixed(2) + suffix : String(v));

  return (
    <div>
      <h2 style={{ margin: '0 0 16px', fontSize: 20, fontWeight: 600 }}>
        <SwapOutlined /> {t('text.strategy.templateAbCompare')}
      </h2>

      <Card style={{ marginBottom: 16 }}>
        <Alert type="info" showIcon style={{ marginBottom: 12 }}
               message={t('text.strategy.templateAbHint', { defaultValue: '选 2-4 个候选（模板+参数），后端并行跑同一段回测，按收益率排冠军。' })} />

        <Row gutter={16} style={{ marginBottom: 12 }}>
          <Col xs={24} md={12}>
            <div style={{ marginBottom: 4 }}>{t('text.strategy.templateAbPeriod', { defaultValue: '回测时段' })}</div>
            <RangePicker value={range} onChange={v => v && setRange(v as [Dayjs, Dayjs])} style={{ width: '100%' }} />
          </Col>
          <Col xs={24} md={6}>
            <div style={{ marginBottom: 4 }}>{t('text.strategy.templateAbCapital', { defaultValue: '初始资金 USDT' })}</div>
            <InputNumber value={initialCapital} onChange={v => setInitialCapital(Number(v) || 10000)}
                         min={100} step={100} style={{ width: '100%' }} />
          </Col>
        </Row>

        {candidates.map((c, i) => (
          <Card key={i} size="small" style={{ marginBottom: 8 }} title={
            <Space>
              <Tag color="blue">{`候选 ${i + 1}`}</Tag>
              {candidates.length > 2 && (
                <Button size="small" danger type="link" icon={<DeleteOutlined />}
                        onClick={() => removeCandidate(i)} />
              )}
            </Space>
          }>
            <Row gutter={16}>
              <Col xs={24} md={8}>
                <div style={{ marginBottom: 4 }}>Template</div>
                <Select
                  style={{ width: '100%' }}
                  value={c.templateId}
                  onChange={v => updateCandidate(i, { templateId: v })}
                  options={templates.map(tp => ({ value: tp.id, label: `${tp.code} · ${tp.name} v${tp.version}` }))}
                  placeholder="Select template"
                />
                <Button size="small" type="link" onClick={() => onFillDefaults(i)}>
                  {t('text.strategy.templateAbFillDefault', { defaultValue: '填入默认参数' })}
                </Button>
              </Col>
              <Col xs={24} md={16}>
                <div style={{ marginBottom: 4 }}>userParams JSON</div>
                <Input.TextArea value={c.paramsJson}
                                onChange={e => updateCandidate(i, { paramsJson: e.target.value })}
                                autoSize={{ minRows: 4, maxRows: 12 }}
                                style={{ fontFamily: 'monospace', fontSize: 12 }} />
              </Col>
            </Row>
          </Card>
        ))}

        <Space>
          <Button icon={<PlusOutlined />} onClick={addCandidate} disabled={candidates.length >= 4}>
            {t('text.strategy.templateAbAddCandidate', { defaultValue: '添加候选' })}
          </Button>
          <Button type="primary" icon={<ThunderboltOutlined />} onClick={onRun} loading={running}>
            {t('text.strategy.templateAbRun', { defaultValue: '开始对比' })}
          </Button>
        </Space>
      </Card>

      {result ? (
        <Card title={t('text.strategy.templateAbResult', { defaultValue: '对比结果' })}>
          {result.winnerIndex != null && (
            <Alert type="success" showIcon style={{ marginBottom: 12 }}
                   message={t('text.strategy.templateAbWinner', { defaultValue: '冠军：候选 {{n}}', n: result.winnerIndex + 1 })} />
          )}
          <Table
            rowKey={(_, i) => String(i)}
            pagination={false}
            dataSource={result.candidates}
            columns={[
              { title: '#', key: 'idx', width: 60,
                render: (_: unknown, _r: unknown, i: number) => (
                  <Tag color={i === result.winnerIndex ? 'gold' : 'default'}>{i + 1}</Tag>
                ) },
              { title: 'Template', key: 'template',
                render: (_: unknown, r: AbCompareCandidate) => {
                  const tpl = templates.find(t => t.id === r.templateId);
                  return tpl ? `${tpl.code} v${r.templateVersion ?? tpl.version}` : String(r.templateId);
                } },
              { title: 'Return %', key: 'returnRate',
                render: (_: unknown, r: AbCompareCandidate) => (
                  <span style={{ color: Number(r.returnRate ?? 0) >= 0 ? '#3f8600' : '#cf1322' }}>
                    {fmt(r.returnRate, '%')}
                  </span>
                ) },
              { title: 'MaxDD %', key: 'maxDrawdown',
                render: (_: unknown, r: AbCompareCandidate) => fmt(r.maxDrawdown, '%') },
              { title: 'WinRate %', key: 'winRate',
                render: (_: unknown, r: AbCompareCandidate) => fmt(r.winRate, '%') },
              { title: 'Trades', dataIndex: 'totalTrades', key: 'totalTrades' },
              { title: 'Sharpe', key: 'sharpe',
                render: (_: unknown, r: AbCompareCandidate) => fmt(r.sharpeRatio) },
              { title: 'PL Ratio', key: 'pl',
                render: (_: unknown, r: AbCompareCandidate) => fmt(r.profitLossRatio) },
              { title: 'Error', dataIndex: 'errorMessage', key: 'errorMessage',
                render: (v: string | null) => v ? <Tag color="error">{v}</Tag> : <Tag color="success">OK</Tag> },
            ]}
          />

          <Row gutter={16} style={{ marginTop: 16 }}>
            {result.candidates.map((c, i) => (
              <Col xs={24} md={12} lg={8} key={i}>
                <Card size="small" title={`候选 ${i + 1}${i === result.winnerIndex ? ' 🏆' : ''}`}>
                  <Statistic title="Return"
                             value={Number(c.returnRate ?? 0)} precision={2} suffix="%"
                             valueStyle={{ color: Number(c.returnRate ?? 0) >= 0 ? '#3f8600' : '#cf1322' }} />
                  <div style={{ marginTop: 8, fontSize: 12, color: '#888' }}>
                    Max DD: {fmt(c.maxDrawdown, '%')} · Win: {fmt(c.winRate, '%')} · Trades: {c.totalTrades ?? 0}
                  </div>
                </Card>
              </Col>
            ))}
          </Row>
        </Card>
      ) : (
        <Empty description={t('text.strategy.templateAbNoResult', { defaultValue: '尚未运行对比' })} />
      )}
    </div>
  );
};

