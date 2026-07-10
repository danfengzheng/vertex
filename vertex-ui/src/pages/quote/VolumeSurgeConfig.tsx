import { useEffect, useState } from 'react';
import {
  Card, Form, InputNumber, Switch, Input, Button, Space, Row, Col,
  Select, Alert, Divider, Descriptions, message, Spin, Tag,
} from 'antd';
import { ReloadOutlined, SaveOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { volumeSurgeApi, VolumeSurgeConfig, VolumeSurgeStatus } from '../../api/quote';
import { formatTimestamp } from '../../utils/date';

/**
 * 币安现货成交量暴增扫描器 - 配置页面。
 * <p>
 * 提交后 5s 内后端缓存失效，下一次心跳（默认 60s）就用新配置。
 * 「总开关」是热切换的，改完保存立即生效。
 * </p>
 */
export const VolumeSurgeConfigPage = () => {
  const { t } = useTranslation();
  const [form] = Form.useForm<VolumeSurgeConfig>();
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [status, setStatus] = useState<VolumeSurgeStatus | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      const [cfgRes, statusRes] = await Promise.all([
        volumeSurgeApi.getConfig(),
        volumeSurgeApi.status(),
      ]);
      if (cfgRes.code !== 200) {
        setError(cfgRes.message || 'Failed to load config');
      } else if (cfgRes.data) {
        form.setFieldsValue({
          ...cfgRes.data,
          // 后端返回 0/1，Switch 期望 boolean —— antd 支持 checked/unchecked value 映射
        });
      }
      setStatus(statusRes.data ?? null);
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
      const res = await volumeSurgeApi.updateConfig({ ...values, id: 1 });
      if (res.code === 200) {
        message.success(t('common.saveSuccess', { defaultValue: '保存成功' }));
        if (res.data) form.setFieldsValue(res.data);
      } else {
        message.error(res.message || 'Failed to save');
      }
    } catch (e) {
      const err = e as { errorFields?: unknown; message?: string };
      if (!err.errorFields) {
        message.error(err.message || 'Failed');
      }
    } finally {
      setSaving(false);
    }
  };

  const StatusBar = () => {
    if (!status) return null;
    if (!status.installed) {
      return (
        <Alert
          type="warning"
          showIcon
          message={t('text.quote.volumeSurgeNotInstalled')}
          style={{ marginBottom: 16 }}
        />
      );
    }
    return (
      <Card size="small" style={{ marginBottom: 16 }}>
        <Descriptions size="small" column={{ xs: 1, sm: 2, md: 3 }}>
          <Descriptions.Item label={t('text.quote.volumeSurgeInstalled')}>
            <Tag color="success">ON</Tag>
          </Descriptions.Item>
          <Descriptions.Item label={t('text.quote.volumeSurgeLastScan')}>
            {status.lastScanAt > 0 ? formatTimestamp(status.lastScanAt) : '-'}
          </Descriptions.Item>
          <Descriptions.Item label={t('text.quote.volumeSurgeLastAlerts')}>
            {status.lastAlertCount}
          </Descriptions.Item>
        </Descriptions>
      </Card>
    );
  };

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h2 style={{ margin: 0, fontSize: 20, fontWeight: 600 }}>
          <ThunderboltOutlined /> {t('text.quote.volumeSurgeTitle')}
        </h2>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={load} loading={loading}>
            {t('common.refresh', { defaultValue: '刷新' })}
          </Button>
          <Button type="primary" icon={<SaveOutlined />} onClick={onSave} loading={saving}>
            {t('common.save', { defaultValue: '保存' })}
          </Button>
        </Space>
      </div>

      {error && <Alert type="error" message={error} style={{ marginBottom: 16 }} />}

      <StatusBar />

      <Spin spinning={loading}>
        <Card>
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 16 }}
            message={t('text.quote.volumeSurgeHint')}
          />

          <Form
            form={form}
            layout="vertical"
            initialValues={{
              enabled: 0,
              scanIntervalMinutes: 15,
              quoteCurrency: 'USDT',
              surgeRatioThreshold: 10,
              minPriceChange1hPct: 2,
              baselineHours: 24,
              minBaselineMedianUsdt: 5000,
              min24hQuoteVolumeUsdt: 50000,
              max24hQuoteVolumeUsdt: 10000000,
              prefilterMinAbs24hPriceChangePct: 3,
              excludeDaysSinceListing: 7,
              cooldownHours: 6,
              alertDirections: 'BOTH',
              includeUnclosedBar: 1,
              telegramEnabled: 0,
            }}
          >
            <Divider titlePlacement="start">{t('text.quote.volumeSurgeSectionMain')}</Divider>
            <Row gutter={24}>
              <Col xs={24} sm={12} md={6}>
                <Form.Item
                  name="enabled"
                  label={t('text.quote.volumeSurgeEnabled')}
                  valuePropName="checked"
                  getValueFromEvent={(checked) => (checked ? 1 : 0)}
                  getValueProps={(v) => ({ checked: v === 1 })}
                >
                  <Switch />
                </Form.Item>
              </Col>
              <Col xs={24} sm={12} md={6}>
                <Form.Item
                  name="scanIntervalMinutes"
                  label={t('text.quote.volumeSurgeScanInterval')}
                  rules={[{ required: true, type: 'number', min: 1 }]}
                >
                  <InputNumber min={1} max={720} style={{ width: '100%' }} addonAfter={t('common.minutes', { defaultValue: '分钟' })} />
                </Form.Item>
              </Col>
              <Col xs={24} sm={12} md={6}>
                <Form.Item
                  name="quoteCurrency"
                  label={t('text.quote.volumeSurgeQuoteCurrency')}
                  rules={[{ required: true }]}
                >
                  <Input placeholder="USDT" />
                </Form.Item>
              </Col>
              <Col xs={24} sm={12} md={6}>
                <Form.Item
                  name="alertDirections"
                  label={t('text.quote.volumeSurgeDirections')}
                >
                  <Select
                    options={[
                      { value: 'BOTH', label: t('text.quote.volumeSurgeDirBoth') },
                      { value: 'UP', label: t('text.quote.volumeSurgeDirUp') },
                      { value: 'DOWN', label: t('text.quote.volumeSurgeDirDown') },
                    ]}
                  />
                </Form.Item>
              </Col>
            </Row>

            <Divider titlePlacement="start">{t('text.quote.volumeSurgeSectionThresholds')}</Divider>
            <Row gutter={24}>
              <Col xs={24} sm={12} md={6}>
                <Form.Item
                  name="surgeRatioThreshold"
                  label={t('text.quote.volumeSurgeRatio')}
                  tooltip={t('text.quote.volumeSurgeRatioTip')}
                  rules={[{ required: true, type: 'number', min: 1 }]}
                >
                  <InputNumber min={1} max={100} step={0.5} style={{ width: '100%' }} addonAfter="x" />
                </Form.Item>
              </Col>
              <Col xs={24} sm={12} md={6}>
                <Form.Item
                  name="minPriceChange1hPct"
                  label={t('text.quote.volumeSurgeMinPricePct')}
                >
                  <InputNumber min={0} max={100} step={0.5} style={{ width: '100%' }} addonAfter="%" />
                </Form.Item>
              </Col>
              <Col xs={24} sm={12} md={6}>
                <Form.Item
                  name="baselineHours"
                  label={t('text.quote.volumeSurgeBaselineHours')}
                  tooltip={t('text.quote.volumeSurgeBaselineHoursTip')}
                >
                  <InputNumber min={6} max={720} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
              <Col xs={24} sm={12} md={6}>
                <Form.Item
                  name="minBaselineMedianUsdt"
                  label={t('text.quote.volumeSurgeMinBaseline')}
                >
                  <InputNumber min={0} step={1000} style={{ width: '100%' }} addonAfter="USDT" />
                </Form.Item>
              </Col>
              <Col xs={24} sm={12} md={6}>
                <Form.Item
                  name="includeUnclosedBar"
                  label={t('text.quote.volumeSurgeIncludeUnclosed')}
                  tooltip={t('text.quote.volumeSurgeIncludeUnclosedTip')}
                  valuePropName="checked"
                  getValueFromEvent={(checked) => (checked ? 1 : 0)}
                  getValueProps={(v) => ({ checked: v === 1 })}
                >
                  <Switch />
                </Form.Item>
              </Col>
            </Row>

            <Divider titlePlacement="start">{t('text.quote.volumeSurgeSectionCandidates')}</Divider>
            <Row gutter={24}>
              <Col xs={24} sm={12} md={6}>
                <Form.Item
                  name="min24hQuoteVolumeUsdt"
                  label={t('text.quote.volumeSurgeMin24h')}
                >
                  <InputNumber min={0} step={10000} style={{ width: '100%' }} addonAfter="USDT" />
                </Form.Item>
              </Col>
              <Col xs={24} sm={12} md={6}>
                <Form.Item
                  name="max24hQuoteVolumeUsdt"
                  label={t('text.quote.volumeSurgeMax24h')}
                >
                  <InputNumber min={0} step={100000} style={{ width: '100%' }} addonAfter="USDT" />
                </Form.Item>
              </Col>
              <Col xs={24} sm={12} md={6}>
                <Form.Item
                  name="prefilterMinAbs24hPriceChangePct"
                  label={t('text.quote.volumeSurgePrefilterPct')}
                >
                  <InputNumber min={0} max={100} step={0.5} style={{ width: '100%' }} addonAfter="%" />
                </Form.Item>
              </Col>
              <Col xs={24} sm={12} md={6}>
                <Form.Item
                  name="excludeDaysSinceListing"
                  label={t('text.quote.volumeSurgeExcludeNewDays')}
                >
                  <InputNumber min={0} max={365} style={{ width: '100%' }} addonAfter={t('common.days', { defaultValue: '天' })} />
                </Form.Item>
              </Col>
              <Col xs={24} sm={12} md={6}>
                <Form.Item
                  name="cooldownHours"
                  label={t('text.quote.volumeSurgeCooldownHours')}
                >
                  <InputNumber min={0} max={168} style={{ width: '100%' }} addonAfter={t('common.hours', { defaultValue: '小时' })} />
                </Form.Item>
              </Col>
            </Row>

            <Divider titlePlacement="start">{t('text.quote.volumeSurgeSectionLists')}</Divider>
            <Row gutter={24}>
              <Col xs={24} md={12}>
                <Form.Item
                  name="symbolBlacklist"
                  label={t('text.quote.volumeSurgeBlacklist')}
                  tooltip={t('text.quote.volumeSurgeCsvHint')}
                >
                  <Input.TextArea rows={2} placeholder="USDCUSDT,FDUSDT,TUSDUSDT,DAIUSDT" />
                </Form.Item>
              </Col>
              <Col xs={24} md={12}>
                <Form.Item
                  name="symbolWhitelist"
                  label={t('text.quote.volumeSurgeWhitelist')}
                  tooltip={t('text.quote.volumeSurgeWhitelistTip')}
                >
                  <Input.TextArea rows={2} placeholder="留空 = 全部" />
                </Form.Item>
              </Col>
            </Row>

            <Divider titlePlacement="start">{t('text.quote.volumeSurgeSectionTelegram')}</Divider>
            <Row gutter={24}>
              <Col xs={24} sm={12} md={6}>
                <Form.Item
                  name="telegramEnabled"
                  label={t('text.quote.volumeSurgeTgEnabled')}
                  valuePropName="checked"
                  getValueFromEvent={(checked) => (checked ? 1 : 0)}
                  getValueProps={(v) => ({ checked: v === 1 })}
                >
                  <Switch />
                </Form.Item>
              </Col>
              <Col xs={24} md={9}>
                <Form.Item
                  name="telegramBotToken"
                  label={t('text.quote.volumeSurgeTgBotToken')}
                >
                  <Input.Password placeholder="bot123:AAABBBccc..." autoComplete="off" />
                </Form.Item>
              </Col>
              <Col xs={24} md={9}>
                <Form.Item
                  name="telegramChatId"
                  label={t('text.quote.volumeSurgeTgChatId')}
                >
                  <Input placeholder="-100..." />
                </Form.Item>
              </Col>
            </Row>
          </Form>
        </Card>
      </Spin>
    </div>
  );
};
