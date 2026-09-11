import { useEffect, useMemo, useState } from 'react';
import { Card, Form, Input, InputNumber, Select, Switch, Button, Space, Alert, Statistic, Row, Col, message, Divider, DatePicker } from 'antd';
import { RocketOutlined, ExperimentOutlined, EyeOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import dayjs, { Dayjs } from 'dayjs';
import { templateApi, StrategyTemplateVO, TemplateExposedParam } from '../../api/template';

const { RangePicker } = DatePicker;

/**
 * "从模板创建策略"页 —— 加载模板 → 动态渲染暴露参数表单 →
 * 一键 [预览] / [快速回测] / [创建策略]。
 */
export const CreateFromTemplate = () => {
  const { t } = useTranslation();
  const nav = useNavigate();
  const { id } = useParams<{ id: string }>();
  const [tpl, setTpl] = useState<StrategyTemplateVO | null>(null);
  const [form] = Form.useForm();
  const [previewJson, setPreviewJson] = useState<unknown>(null);
  const [backtestResult, setBacktestResult] = useState<Record<string, unknown> | null>(null);
  const [backtestRange, setBacktestRange] = useState<[Dayjs, Dayjs]>([
    dayjs().subtract(30, 'day'), dayjs(),
  ]);
  const [initialCapital, setInitialCapital] = useState(10000);
  const [loading, setLoading] = useState(false);
  const [creating, setCreating] = useState(false);
  const [previewing, setPreviewing] = useState(false);
  const [running, setRunning] = useState(false);

  const load = async () => {
    if (!id) return;
    setLoading(true);
    try {
      // 用 string 传，Number() 会让 Long 型 id 精度丢失导致 404
      const res = await templateApi.get(id!);
      if (res.code === 200 && res.data) {
        setTpl(res.data);
        // set defaults from exposedParams
        const defaults: Record<string, unknown> = {};
        (res.data.exposedParams ?? []).forEach(p => {
          if (p.default !== undefined && p.default !== null) defaults[p.userKey] = p.default;
        });
        form.setFieldsValue(defaults);
      }
    } finally {
      setLoading(false);
    }
  };
  useEffect(() => { load(); /* eslint-disable-next-line */ }, [id]);

  const collectParams = async (): Promise<Record<string, unknown>> => {
    const values = await form.validateFields();
    return values;
  };

  const onPreview = async () => {
    if (!tpl) return;
    setPreviewing(true);
    try {
      const params = await collectParams();
      const res = await templateApi.preview({ templateId: tpl.id, userParams: params });
      if (res.code === 200) setPreviewJson(res.data);
      else message.error(res.message || 'preview failed');
    } catch (e) {
      const err = e as { errorFields?: unknown; message?: string };
      if (!err.errorFields) message.error(err.message || 'preview failed');
    } finally {
      setPreviewing(false);
    }
  };

  const onQuickBacktest = async () => {
    if (!tpl) return;
    setRunning(true);
    setBacktestResult(null);
    try {
      const params = await collectParams();
      const res = await templateApi.quickBacktest({
        templateId: tpl.id,
        userParams: params,
        backtestStartTime: backtestRange[0].valueOf(),
        backtestEndTime: backtestRange[1].valueOf(),
        backtestInitialCapital: initialCapital,
      });
      if (res.code === 200 && res.data) {
        setBacktestResult(res.data as Record<string, unknown>);
        message.success(t('text.strategy.templateQuickBacktestDone', { defaultValue: '快速回测完成' }));
      } else {
        message.error(res.message || 'backtest failed');
      }
    } catch (e) {
      const err = e as { errorFields?: unknown; message?: string };
      if (!err.errorFields) message.error(err.message || 'backtest failed');
    } finally {
      setRunning(false);
    }
  };

  const onCreate = async () => {
    if (!tpl) return;
    setCreating(true);
    try {
      const params = await collectParams();
      const res = await templateApi.build({ templateId: tpl.id, userParams: params });
      if (res.code === 200 && res.data) {
        message.success(t('text.strategy.templateCreatedOk', { defaultValue: '策略创建成功' }));
        nav('/strategy/config');
      } else {
        message.error(res.message || 'create failed');
      }
    } catch (e) {
      const err = e as { errorFields?: unknown; message?: string };
      if (!err.errorFields) message.error(err.message || 'create failed');
    } finally {
      setCreating(false);
    }
  };

  const renderInput = (p: TemplateExposedParam) => {
    switch (p.type) {
      case 'number':
        return <InputNumber min={p.min} max={p.max} step={p.step}
                             style={{ width: '100%' }} placeholder={String(p.default ?? '')} />;
      case 'bool':
        return <Switch checkedChildren="ON" unCheckedChildren="OFF" />;
      case 'enum':
        return (
          <Select options={(p.options ?? []).map(o => ({ value: o, label: String(o) }))}
                  placeholder={String(p.default ?? '')}
                  allowClear={!p.required} />
        );
      default:
        return <Input placeholder={String(p.default ?? '')} />;
    }
  };

  const valuePropsForBool = (p: TemplateExposedParam) =>
    p.type === 'bool' ? { valuePropName: 'checked',
      getValueFromEvent: (v: boolean) => (v ? 1 : 0),
      getValueProps: (v: unknown) => ({ checked: v === 1 || v === true }) } : {};

  const bResult = useMemo(() => backtestResult ?? {}, [backtestResult]);

  return (
    <div>
      <div style={{ marginBottom: 16 }}>
        <h2 style={{ margin: 0, fontSize: 20, fontWeight: 600 }}>
          <RocketOutlined /> {t('text.strategy.templateCreateFrom')}
          {tpl && <span style={{ marginLeft: 8, color: '#888', fontWeight: 400 }}>· {tpl.name} v{tpl.version}</span>}
        </h2>
      </div>

      {tpl && (
        <Card loading={loading} style={{ marginBottom: 16 }}>
          {tpl.description && <Alert type="info" showIcon message={tpl.description} style={{ marginBottom: 12 }} />}

          <Form form={form} layout="vertical">
            <Row gutter={16}>
              {(tpl.exposedParams ?? []).map(p => (
                <Col xs={24} sm={12} md={8} key={p.userKey}>
                  <Form.Item
                    name={p.userKey}
                    label={p.label ?? p.userKey}
                    tooltip={p.description}
                    rules={p.required ? [{ required: true, message: `${p.label ?? p.userKey} 必填` }] : []}
                    {...valuePropsForBool(p)}
                  >
                    {renderInput(p)}
                  </Form.Item>
                </Col>
              ))}
            </Row>

            <Divider titlePlacement="start">{t('text.strategy.templateBacktestRange', { defaultValue: '快速回测区间' })}</Divider>
            <Row gutter={16}>
              <Col xs={24} md={12}>
                <Form.Item label={t('text.strategy.templateBacktestPeriod', { defaultValue: '回测时间段' })}>
                  <RangePicker value={backtestRange} onChange={v => v && setBacktestRange(v as [Dayjs, Dayjs])}
                               style={{ width: '100%' }} />
                </Form.Item>
              </Col>
              <Col xs={24} md={6}>
                <Form.Item label={t('text.strategy.templateBacktestCapital', { defaultValue: '初始资金 USDT' })}>
                  <InputNumber value={initialCapital} onChange={v => setInitialCapital(Number(v) || 10000)}
                               min={100} step={100} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
            </Row>
          </Form>

          <Space wrap>
            <Button icon={<EyeOutlined />} onClick={onPreview} loading={previewing}>
              {t('text.strategy.templatePreviewBtn', { defaultValue: '预览生成结构' })}
            </Button>
            <Button icon={<ThunderboltOutlined />} onClick={onQuickBacktest} loading={running}>
              {t('text.strategy.templateQuickBacktestBtn', { defaultValue: '快速回测' })}
            </Button>
            <Button type="primary" icon={<RocketOutlined />} onClick={onCreate} loading={creating}>
              {t('text.strategy.templateCreateBtn', { defaultValue: '创建策略' })}
            </Button>
          </Space>
        </Card>
      )}

      {backtestResult && (
        <Card style={{ marginBottom: 16 }} title={<><ExperimentOutlined /> {t('text.strategy.templateBacktestSummary', { defaultValue: '快速回测摘要' })}</>}>
          <Row gutter={16}>
            <Col xs={12} md={6}>
              <Statistic title={t('text.strategy.tplStatReturn', { defaultValue: '收益率' })}
                         value={Number(bResult.returnRate ?? 0)} precision={2} suffix="%"
                         valueStyle={{ color: Number(bResult.returnRate ?? 0) >= 0 ? '#3f8600' : '#cf1322' }} />
            </Col>
            <Col xs={12} md={6}>
              <Statistic title={t('text.strategy.tplStatMaxDd', { defaultValue: '最大回撤' })}
                         value={Number(bResult.maxDrawdown ?? 0)} precision={2} suffix="%"
                         valueStyle={{ color: '#cf1322' }} />
            </Col>
            <Col xs={12} md={6}>
              <Statistic title={t('text.strategy.tplStatWinRate', { defaultValue: '胜率' })}
                         value={Number(bResult.winRate ?? 0)} precision={2} suffix="%" />
            </Col>
            <Col xs={12} md={6}>
              <Statistic title={t('text.strategy.tplStatTrades', { defaultValue: '交易次数' })}
                         value={Number(bResult.totalTrades ?? 0)} />
            </Col>
          </Row>
        </Card>
      )}

      {previewJson != null && (
        <Card title={t('text.strategy.templatePreviewJson', { defaultValue: '生成的策略结构（预览，未落库）' })}>
          <pre style={{ background: '#fafafa', padding: 12, maxHeight: 400, overflow: 'auto', fontSize: 12 }}>
            {JSON.stringify(previewJson, null, 2)}
          </pre>
        </Card>
      )}
    </div>
  );
};
