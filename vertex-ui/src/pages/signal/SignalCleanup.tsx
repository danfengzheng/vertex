import { useEffect, useState } from 'react';
import {
  Card, Form, InputNumber, Input, Switch, Select, Button, Space, Row, Col,
  Alert, Divider, message, Spin, Modal, Descriptions, Tag, Statistic,
} from 'antd';
import {
  ReloadOutlined, SaveOutlined, DeleteOutlined, EyeOutlined, ThunderboltOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import {
  signalCleanupApi, SignalCleanupConfigVO, SignalCleanupPreviewVO, SignalCleanupRunResultVO,
} from '../../api/signalCleanup';

/**
 * 信号清理配置 & 手动触发页。
 * <p>
 * DB 表 signal_cleanup_config 单行；5s 缓存。定时任务每分钟 tick 检查 cron。
 * 硬删除时会级联清 RocksDB 中 ai:rt:{signalId} 的 AI 分析。
 * </p>
 */
export const SignalCleanup = () => {
  const { t } = useTranslation();
  const [form] = Form.useForm<SignalCleanupConfigVO>();
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [previewing, setPreviewing] = useState(false);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [preview, setPreview] = useState<SignalCleanupPreviewVO | null>(null);
  const [lastRunView, setLastRunView] = useState<{
    lastRunAt: string | null | undefined;
    lastDeletedTotal: number;
    lastDurationMs: number | null | undefined;
    lastError: string | null | undefined;
  }>({ lastRunAt: null, lastDeletedTotal: 0, lastDurationMs: 0, lastError: null });

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await signalCleanupApi.getConfig();
      if (res.code !== 200) {
        setError(res.message || 'Failed to load config');
      } else if (res.data) {
        form.setFieldsValue(res.data);
        const total = (res.data.lastRunDeletedNeutral ?? 0)
          + (res.data.lastRunDeletedDirectional ?? 0)
          + (res.data.lastRunDeletedLinked ?? 0);
        setLastRunView({
          lastRunAt: res.data.lastRunAt,
          lastDeletedTotal: total,
          lastDurationMs: res.data.lastRunDurationMs,
          lastError: res.data.lastRunError,
        });
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : 'unknown');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const onSave = async () => {
    try {
      const values = await form.validateFields();
      setSaving(true);
      const res = await signalCleanupApi.updateConfig({ ...values, id: 1 });
      if (res.code === 200) {
        message.success(t('common.saveSuccess', { defaultValue: '保存成功，5 秒内生效' }));
        if (res.data) form.setFieldsValue(res.data);
      } else {
        message.error(res.message || 'Failed to save');
      }
    } catch (e) {
      const err = e as { errorFields?: unknown; message?: string };
      if (!err.errorFields) message.error(err.message || 'Failed');
    } finally {
      setSaving(false);
    }
  };

  const onPreview = async () => {
    setPreviewing(true);
    try {
      const res = await signalCleanupApi.preview();
      if (res.code === 200 && res.data) {
        setPreview(res.data);
      } else {
        message.error(res.message || 'preview failed');
      }
    } catch (e) {
      message.error(e instanceof Error ? e.message : 'preview failed');
    } finally {
      setPreviewing(false);
    }
  };

  const doRun = async () => {
    setRunning(true);
    try {
      const res = await signalCleanupApi.run();
      if (res.code !== 200 || !res.data) {
        message.error(res.message || 'run failed');
        return;
      }
      const r: SignalCleanupRunResultVO = res.data;
      if (r.errorMessage && r.deletedTotal === 0) {
        message.warning(t('text.strategy.cleanupRunSkipped', { reason: r.errorMessage }));
      } else {
        message.success(t('text.strategy.cleanupRunOk', { count: r.deletedTotal, ms: r.durationMs }));
      }
      // 重新拉一次配置，把最近一次统计也刷新
      await load();
      await onPreview();
    } finally {
      setRunning(false);
    }
  };

  const onRunNow = async () => {
    // 未预览过 → 先跑一次预览，让用户看清将删多少
    let p = preview;
    if (!p) {
      setPreviewing(true);
      try {
        const res = await signalCleanupApi.preview();
        if (res.code === 200 && res.data) {
          setPreview(res.data);
          p = res.data;
        } else {
          message.error(res.message || 'preview failed');
          return;
        }
      } finally {
        setPreviewing(false);
      }
    }
    Modal.confirm({
      title: t('text.strategy.cleanupRunNow'),
      icon: <ThunderboltOutlined />,
      content: t('text.strategy.cleanupRunConfirm', {
        count: p!.willDeleteTotal,
        after: p!.afterCleanup,
      }),
      okType: 'danger',
      onOk: doRun,
    });
  };

  const fmtTs = (ms: number | null | undefined) =>
    ms == null ? '—' : new Date(ms).toLocaleString();

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h2 style={{ margin: 0, fontSize: 20, fontWeight: 600 }}>
          <DeleteOutlined /> {t('text.strategy.cleanupTitle')}
        </h2>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={load} loading={loading}>
            {t('common.refresh', { defaultValue: '刷新' })}
          </Button>
          <Button icon={<EyeOutlined />} onClick={onPreview} loading={previewing}>
            {t('text.strategy.cleanupPreview')}
          </Button>
          <Button danger icon={<ThunderboltOutlined />} onClick={onRunNow} loading={running}>
            {t('text.strategy.cleanupRunNow')}
          </Button>
          <Button type="primary" icon={<SaveOutlined />} onClick={onSave} loading={saving}>
            {t('common.save', { defaultValue: '保存' })}
          </Button>
        </Space>
      </div>

      {error && <Alert type="error" message={error} style={{ marginBottom: 16 }} />}

      <Spin spinning={loading}>
        <Card>
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 16 }}
            message={t('text.strategy.cleanupHintDb')}
          />

          <Form
            form={form}
            layout="vertical"
            initialValues={{
              enabled: 0,
              keepNeutralDays: 7,
              keepDirectionalDays: 30,
              keepLinkedDays: 365,
              protectRecentDays: 3,
              scheduleCron: '0 0 3 * * ?',
              deleteMode: 'SOFT',
              batchSize: 1000,
            }}
          >
            <Divider titlePlacement="start">{t('text.strategy.cleanupSectionMain')}</Divider>
            <Row gutter={24}>
              <Col xs={24} sm={12} md={6}>
                <Form.Item
                  name="enabled"
                  label={t('text.strategy.cleanupEnabled')}
                  tooltip={t('text.strategy.cleanupEnabledTip')}
                  valuePropName="checked"
                  getValueFromEvent={(checked) => (checked ? 1 : 0)}
                  getValueProps={(v) => ({ checked: v === 1 })}
                >
                  <Switch />
                </Form.Item>
              </Col>
              <Col xs={24} sm={12} md={9}>
                <Form.Item
                  name="scheduleCron"
                  label={t('text.strategy.cleanupCron')}
                  tooltip={t('text.strategy.cleanupCronTip')}
                  rules={[{ required: true }]}
                >
                  <Input placeholder="0 0 3 * * ?" />
                </Form.Item>
              </Col>
              <Col xs={24} sm={12} md={9}>
                <Form.Item
                  name="deleteMode"
                  label={t('text.strategy.cleanupDeleteMode')}
                  rules={[{ required: true }]}
                >
                  <Select
                    options={[
                      { value: 'SOFT', label: t('text.strategy.cleanupDeleteModeSoft') },
                      { value: 'HARD', label: t('text.strategy.cleanupDeleteModeHard') },
                    ]}
                  />
                </Form.Item>
              </Col>
            </Row>

            <Divider titlePlacement="start">{t('text.strategy.cleanupSectionTtl')}</Divider>
            <Row gutter={24}>
              <Col xs={24} sm={12} md={8}>
                <Form.Item
                  name="keepNeutralDays"
                  label={t('text.strategy.cleanupKeepNeutralDays')}
                  tooltip={t('text.strategy.cleanupKeepNeutralDaysTip')}
                >
                  <InputNumber min={1} max={3650} style={{ width: '100%' }} addonAfter="d" />
                </Form.Item>
              </Col>
              <Col xs={24} sm={12} md={8}>
                <Form.Item
                  name="keepDirectionalDays"
                  label={t('text.strategy.cleanupKeepDirectionalDays')}
                  tooltip={t('text.strategy.cleanupKeepDirectionalDaysTip')}
                >
                  <InputNumber min={1} max={3650} style={{ width: '100%' }} addonAfter="d" />
                </Form.Item>
              </Col>
              <Col xs={24} sm={12} md={8}>
                <Form.Item
                  name="keepLinkedDays"
                  label={t('text.strategy.cleanupKeepLinkedDays')}
                  tooltip={t('text.strategy.cleanupKeepLinkedDaysTip')}
                >
                  <InputNumber min={1} max={3650} style={{ width: '100%' }} addonAfter="d" />
                </Form.Item>
              </Col>
            </Row>

            <Divider titlePlacement="start">{t('text.strategy.cleanupSectionAdvanced')}</Divider>
            <Row gutter={24}>
              <Col xs={24} sm={12} md={8}>
                <Form.Item
                  name="protectRecentDays"
                  label={t('text.strategy.cleanupProtectRecentDays')}
                  tooltip={t('text.strategy.cleanupProtectRecentDaysTip')}
                  rules={[{ required: true }]}
                >
                  <InputNumber min={0} max={365} style={{ width: '100%' }} addonAfter="d" />
                </Form.Item>
              </Col>
              <Col xs={24} sm={12} md={8}>
                <Form.Item
                  name="batchSize"
                  label={t('text.strategy.cleanupBatchSize')}
                  tooltip={t('text.strategy.cleanupBatchSizeTip')}
                  rules={[{ required: true }]}
                >
                  <InputNumber min={100} max={10000} step={100} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
            </Row>

            <Divider titlePlacement="start">{t('text.strategy.cleanupSectionLastRun')}</Divider>
            {lastRunView.lastRunAt ? (
              <Row gutter={24}>
                <Col xs={12} md={6}>
                  <Statistic
                    title={t('text.strategy.cleanupLastRunAt')}
                    value={String(lastRunView.lastRunAt).replace('T', ' ')}
                    valueStyle={{ fontSize: 14 }}
                  />
                </Col>
                <Col xs={12} md={6}>
                  <Statistic
                    title={t('text.strategy.cleanupLastRunDeletedTotal')}
                    value={lastRunView.lastDeletedTotal}
                  />
                </Col>
                <Col xs={12} md={6}>
                  <Statistic
                    title={t('text.strategy.cleanupLastRunDuration')}
                    value={lastRunView.lastDurationMs ?? 0}
                    suffix="ms"
                  />
                </Col>
                <Col xs={12} md={6}>
                  {lastRunView.lastError
                    ? <Tag color="error">{lastRunView.lastError}</Tag>
                    : <Tag color="success">OK</Tag>}
                </Col>
              </Row>
            ) : (
              <div style={{ color: '#999' }}>{t('text.strategy.cleanupNeverRun')}</div>
            )}
          </Form>
        </Card>

        {preview && (
          <Card style={{ marginTop: 16 }} title={t('text.strategy.cleanupPreview')}>
            <Row gutter={16} style={{ marginBottom: 16 }}>
              <Col xs={12} md={6}>
                <Statistic
                  title={t('text.strategy.cleanupPreviewTotalActive')}
                  value={preview.totalActive}
                />
              </Col>
              <Col xs={12} md={6}>
                <Statistic
                  title={t('text.strategy.cleanupPreviewWillDelete')}
                  value={preview.willDeleteTotal}
                  valueStyle={{ color: preview.willDeleteTotal > 0 ? '#cf1322' : undefined }}
                />
              </Col>
              <Col xs={12} md={6}>
                <Statistic
                  title={t('text.strategy.cleanupPreviewAfter')}
                  value={preview.afterCleanup}
                  valueStyle={{ color: '#3f8600' }}
                />
              </Col>
              <Col xs={12} md={6}>
                <Statistic
                  title={t('text.strategy.cleanupPreviewProtect')}
                  value={fmtTs(preview.protectCutoffMs)}
                  valueStyle={{ fontSize: 14 }}
                />
              </Col>
            </Row>
            <Descriptions bordered size="small" column={1}>
              <Descriptions.Item label={t('text.strategy.cleanupPreviewNeutral')}>
                {preview.willDeleteNeutral} · {t('text.strategy.cleanupPreviewCutoff')}: {fmtTs(preview.neutralCutoffMs)}
              </Descriptions.Item>
              <Descriptions.Item label={t('text.strategy.cleanupPreviewDirectional')}>
                {preview.willDeleteDirectionalOrphan} · {t('text.strategy.cleanupPreviewCutoff')}: {fmtTs(preview.directionalCutoffMs)}
              </Descriptions.Item>
              <Descriptions.Item label={t('text.strategy.cleanupPreviewLinked')}>
                {preview.willDeleteLinked} · {t('text.strategy.cleanupPreviewCutoff')}: {fmtTs(preview.linkedCutoffMs)}
              </Descriptions.Item>
            </Descriptions>
          </Card>
        )}
      </Spin>
    </div>
  );
};
