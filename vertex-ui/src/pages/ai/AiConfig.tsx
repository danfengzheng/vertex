import { useEffect, useState } from 'react';
import {
  Card, Form, Input, InputNumber, Switch, Select, Button, Space, Row, Col,
  Alert, Divider, message, Spin,
} from 'antd';
import { ReloadOutlined, SaveOutlined, SettingOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { aiApi, AiConfigVO } from '../../api/ai';

/**
 * AI 模块动态配置页。
 * <p>
 * 对应 DB 表 ai_config 单行。保存后 5s 内所有 AI 调用（信号 / 回测 trade / dashboard）
 * 都会用新配置，不需要重启。
 * </p>
 */
export const AiConfig = () => {
  const { t } = useTranslation();
  const [form] = Form.useForm<AiConfigVO>();
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await aiApi.getConfig();
      if (res.code !== 200) {
        setError(res.message || 'Failed to load config');
      } else if (res.data) {
        form.setFieldsValue(res.data);
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
      const res = await aiApi.updateConfig({ ...values, id: 1 });
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

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h2 style={{ margin: 0, fontSize: 20, fontWeight: 600 }}>
          <SettingOutlined /> {t('text.ai.configTitle')}
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

      <Spin spinning={loading}>
        <Card>
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 16 }}
            message={t('text.ai.configHintDb')}
          />
          <Form
            form={form}
            layout="vertical"
            initialValues={{
              enabled: 0,
              provider: 'gemini',
              language: 'zh-CN',
              geminiModel: 'gemini-2.0-flash',
              geminiBaseUrl: 'https://generativelanguage.googleapis.com',
              geminiTimeoutSeconds: 30,
              geminiMaxRetry: 2,
              deepseekModel: 'deepseek-chat',
              deepseekBaseUrl: 'https://api.deepseek.com',
              deepseekTimeoutSeconds: 60,
              deepseekMaxRetry: 2,
              deepseekThinkingEnabled: 0,
              deepseekReasoningEffort: null,
            }}
          >
            <Divider titlePlacement="start">{t('text.ai.sectionMain')}</Divider>
            <Row gutter={24}>
              <Col xs={24} sm={12} md={6}>
                <Form.Item
                  name="enabled"
                  label={t('text.ai.enabledSwitch')}
                  valuePropName="checked"
                  getValueFromEvent={(checked) => (checked ? 1 : 0)}
                  getValueProps={(v) => ({ checked: v === 1 })}
                >
                  <Switch />
                </Form.Item>
              </Col>
              <Col xs={24} sm={12} md={6}>
                <Form.Item name="provider" label={t('text.ai.providerLabel')} rules={[{ required: true }]}>
                  <Select
                    options={[
                      { value: 'gemini', label: 'Google Gemini' },
                      { value: 'deepseek', label: 'DeepSeek' },
                    ]}
                  />
                </Form.Item>
              </Col>
              <Col xs={24} sm={12} md={6}>
                <Form.Item
                  name="language"
                  label={t('text.ai.language')}
                  tooltip={t('text.ai.languageTip')}
                  rules={[{ required: true }]}
                >
                  <Select
                    options={[
                      { value: 'zh-CN', label: '中文（简体）' },
                      { value: 'en',    label: 'English' },
                      { value: 'ja',    label: '日本語' },
                      { value: 'ko',    label: '한국어' },
                    ]}
                  />
                </Form.Item>
              </Col>
            </Row>

            <Divider titlePlacement="start">Google Gemini</Divider>
            <Row gutter={24}>
              <Col xs={24} md={12}>
                <Form.Item name="geminiApiKey" label="API Key" tooltip="aistudio.google.com/app/apikey 申请，免费层 1500 次/天">
                  <Input.Password placeholder="AIzaSy..." autoComplete="off" />
                </Form.Item>
              </Col>
              <Col xs={24} sm={12} md={6}>
                <Form.Item name="geminiModel" label={t('text.ai.model')} rules={[{ required: true }]}>
                  <Input placeholder="gemini-2.0-flash" />
                </Form.Item>
              </Col>
              <Col xs={24} sm={12} md={6}>
                <Form.Item name="geminiBaseUrl" label="Base URL" tooltip={t('text.ai.baseUrlTip')}>
                  <Input placeholder="https://generativelanguage.googleapis.com" />
                </Form.Item>
              </Col>
              <Col xs={24} sm={12} md={6}>
                <Form.Item name="geminiTimeoutSeconds" label={t('text.ai.timeoutSec')}>
                  <InputNumber min={5} max={300} style={{ width: '100%' }} addonAfter="s" />
                </Form.Item>
              </Col>
              <Col xs={24} sm={12} md={6}>
                <Form.Item name="geminiMaxRetry" label={t('text.ai.maxRetry')}>
                  <InputNumber min={0} max={10} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
            </Row>

            <Divider titlePlacement="start">DeepSeek</Divider>
            <Row gutter={24}>
              <Col xs={24} md={12}>
                <Form.Item name="deepseekApiKey" label="API Key" tooltip="platform.deepseek.com 申请">
                  <Input.Password placeholder="sk-..." autoComplete="off" />
                </Form.Item>
              </Col>
              <Col xs={24} sm={12} md={6}>
                <Form.Item name="deepseekModel" label={t('text.ai.model')} rules={[{ required: true }]}>
                  <Input placeholder="deepseek-chat" />
                </Form.Item>
              </Col>
              <Col xs={24} sm={12} md={6}>
                <Form.Item name="deepseekBaseUrl" label="Base URL">
                  <Input placeholder="https://api.deepseek.com" />
                </Form.Item>
              </Col>
              <Col xs={24} sm={12} md={6}>
                <Form.Item name="deepseekTimeoutSeconds" label={t('text.ai.timeoutSec')}>
                  <InputNumber min={5} max={300} style={{ width: '100%' }} addonAfter="s" />
                </Form.Item>
              </Col>
              <Col xs={24} sm={12} md={6}>
                <Form.Item name="deepseekMaxRetry" label={t('text.ai.maxRetry')}>
                  <InputNumber min={0} max={10} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
              <Col xs={24} sm={12} md={6}>
                <Form.Item
                  name="deepseekThinkingEnabled"
                  label={t('text.ai.dsThinking')}
                  tooltip={t('text.ai.dsThinkingTip')}
                  valuePropName="checked"
                  getValueFromEvent={(checked) => (checked ? 1 : 0)}
                  getValueProps={(v) => ({ checked: v === 1 })}
                >
                  <Switch />
                </Form.Item>
              </Col>
              <Col xs={24} sm={12} md={6}>
                <Form.Item
                  name="deepseekReasoningEffort"
                  label={t('text.ai.dsReasoningEffort')}
                  tooltip={t('text.ai.dsReasoningEffortTip')}
                >
                  <Select
                    allowClear
                    placeholder={t('text.ai.dsReasoningEffortDefault')}
                    options={[
                      { value: 'low',    label: 'low' },
                      { value: 'medium', label: 'medium' },
                      { value: 'high',   label: 'high' },
                    ]}
                  />
                </Form.Item>
              </Col>
            </Row>
          </Form>
        </Card>
      </Spin>
    </div>
  );
};
