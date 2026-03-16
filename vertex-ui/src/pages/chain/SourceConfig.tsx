import { useState, useEffect } from 'react';
import {
  Card, Switch, Form, InputNumber, Button, Space, message, Spin,
  Row, Col, Divider, Typography, Tag, Alert,
} from 'antd';
import {
  SaveOutlined, ReloadOutlined, ExperimentOutlined,
} from '@ant-design/icons';
import {
  sourceConfigApi,
  chainTokenApi,
  ChainSourceConfigVO,
  ChainSourceConfigUpdateDTO,
} from '../../api/chain';

const { Title, Text } = Typography;

// ─── 辅助：单个数据源配置卡片 ─────────────────────────────

interface SourceCardProps {
  config: ChainSourceConfigVO;
  onSaved: () => void;
}

const SourceCard = ({ config, onSaved }: SourceCardProps) => {
  const [form] = Form.useForm();
  const [saving, setSaving] = useState(false);
  const [enabled, setEnabled] = useState(config.enabled === 1);

  useEffect(() => {
    setEnabled(config.enabled === 1);
    form.setFieldsValue({
      minMarketCapUsd: config.minMarketCapUsd,
      maxMarketCapUsd: config.maxMarketCapUsd,
      minLiquidityUsd: config.minLiquidityUsd,
      minVolumeLiquidityRatio: config.minVolumeLiquidityRatio,
      minPriceChange1hPct: config.minPriceChange1hPct,
      pageSize: config.pageSize,
      scanLimit: config.scanLimit,
    });
  }, [config, form]);

  const handleSave = async () => {
    try {
      const values = await form.validateFields();
      setSaving(true);
      const dto: ChainSourceConfigUpdateDTO = {
        id: config.id,
        enabled: enabled ? 1 : 0,
        ...values,
      };
      await sourceConfigApi.update(dto);
      message.success('保存成功');
      onSaved();
    } catch {
      // validation or http error
    } finally {
      setSaving(false);
    }
  };

  const isAlpha = config.sourceId === 'bnb_alpha';
  const isTrending = config.sourceId === 'bnb_trending';

  return (
    <Card
      title={
        <Space>
          <Tag color={isAlpha ? 'blue' : 'volcano'}>
            {isAlpha ? '🅰 Binance Alpha' : '📈 BSC DEX 趋势'}
          </Tag>
          <Text strong>{config.sourceName}</Text>
        </Space>
      }
      extra={
        <Space>
          <Text type="secondary" style={{ fontSize: 12 }}>启用</Text>
          <Switch
            checked={enabled}
            onChange={setEnabled}
            checkedChildren="ON"
            unCheckedChildren="OFF"
          />
        </Space>
      }
      style={{ marginBottom: 24 }}
    >
      {!enabled && (
        <Alert
          type="warning"
          message="数据源已禁用，不会在定时扫描中采集此来源的代币"
          showIcon
          style={{ marginBottom: 16 }}
        />
      )}

      <Form form={form} layout="vertical">
        <Row gutter={16}>
          <Col span={12}>
            <Form.Item
              name="minMarketCapUsd"
              label="最低市值（USD）"
              tooltip="低于此市值的代币将被跳过，留空表示不限"
            >
              <InputNumber
                min={0}
                style={{ width: '100%' }}
                placeholder="留空不限，如 500000"
                addonBefore="$"
                step={1000}
              />
            </Form.Item>
          </Col>
          {isTrending && (
            <Col span={12}>
              <Form.Item
                name="maxMarketCapUsd"
                label="最高市值（USD）"
                tooltip="高于此市值的代币将被跳过（过滤大盘币），留空表示不限"
              >
                <InputNumber
                  min={0}
                  style={{ width: '100%' }}
                  placeholder="留空不限，如 50000000"
                  addonBefore="$"
                  step={1000000}
                />
              </Form.Item>
            </Col>
          )}
          <Col span={12}>
            <Form.Item
              name="minLiquidityUsd"
              label="最低流动性（USD）"
              tooltip="低于此流动性的代币将被跳过，留空表示不限"
            >
              <InputNumber
                min={0}
                style={{ width: '100%' }}
                placeholder="留空不限，如 10000"
                addonBefore="$"
                step={1000}
              />
            </Form.Item>
          </Col>
          {isTrending && (
            <>
              <Col span={12}>
                <Form.Item
                  name="minVolumeLiquidityRatio"
                  label="最低换手率（成交量/流动性）"
                  tooltip="24h 成交量/流动性比值，越高代表活跃度越强"
                >
                  <InputNumber
                    min={0}
                    max={100}
                    step={0.1}
                    style={{ width: '100%' }}
                    placeholder="如：0.3"
                  />
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item
                  name="minPriceChange1hPct"
                  label="1h 价格最低涨幅（%）"
                  tooltip="过滤下跌趋势代币，可设为负数允许微跌"
                >
                  <InputNumber
                    style={{ width: '100%' }}
                    placeholder="如：0"
                    addonAfter="%"
                  />
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item
                  name="scanLimit"
                  label="每次扫描代币数量上限"
                  tooltip="每次定时扫描最多处理的代币数量"
                >
                  <InputNumber
                    min={1}
                    max={500}
                    style={{ width: '100%' }}
                    placeholder="如：50"
                  />
                </Form.Item>
              </Col>
            </>
          )}
          {isAlpha && (
            <Col span={12}>
              <Form.Item
                name="pageSize"
                label="每次拉取代币数量"
                tooltip="从 Binance Alpha API 每次拉取的代币数量"
              >
                <InputNumber
                  min={1}
                  max={200}
                  style={{ width: '100%' }}
                  placeholder="如：50"
                />
              </Form.Item>
            </Col>
          )}
        </Row>
      </Form>

      <Divider style={{ margin: '12px 0' }} />

      <div style={{ textAlign: 'right' }}>
        <Button
          type="primary"
          icon={<SaveOutlined />}
          loading={saving}
          onClick={handleSave}
        >
          保存配置
        </Button>
      </div>
    </Card>
  );
};

// ─── 主页面 ──────────────────────────────────────────────

export const SourceConfig = () => {
  const [loading, setLoading] = useState(false);
  const [configs, setConfigs] = useState<ChainSourceConfigVO[]>([]);
  const [scanning, setScanning] = useState(false);

  const fetchConfigs = async () => {
    setLoading(true);
    try {
      const res = await sourceConfigApi.listAll();
      setConfigs(res.data || []);
    } catch {
      // handled by interceptor
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchConfigs();
  }, []);

  const handleScan = async () => {
    setScanning(true);
    try {
      await chainTokenApi.scan('BNB');
      message.success('已触发扫描，约 30 秒后可在代币列表查看结果');
    } catch {
      // handled
    } finally {
      setScanning(false);
    }
  };

  return (
    <div>
      {/* 页面头部 */}
      <div style={{ marginBottom: 24, display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <div>
          <Title level={5} style={{ margin: 0 }}>山寨币筛选数据源配置</Title>
          <Text type="secondary" style={{ fontSize: 13 }}>
            配置 Binance Alpha 和 BSC DEX 趋势筛选的运行参数，无需重启服务即时生效
          </Text>
        </div>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={fetchConfigs} loading={loading}>
            刷新
          </Button>
          <Button
            type="primary"
            icon={<ExperimentOutlined />}
            onClick={handleScan}
            loading={scanning}
          >
            立即触发扫描
          </Button>
        </Space>
      </div>

      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 24 }}
        message="配置说明"
        description={
          <ul style={{ margin: 0, paddingLeft: 16 }}>
            <li>启用数据源后，系统每 5~15 分钟自动扫描一次（含随机抖动）</li>
            <li>筛选模式下，已存在的代币也会刷新指标和评分</li>
            <li>可点击「立即触发扫描」手动测试，结果会在代币列表中显示</li>
          </ul>
        }
      />

      <Spin spinning={loading}>
        {configs.map(cfg => (
          <SourceCard key={cfg.sourceId} config={cfg} onSaved={fetchConfigs} />
        ))}
        {!loading && configs.length === 0 && (
          <Card>
            <Text type="secondary">暂无数据源配置，请执行数据库迁移脚本 chain_source_config.sql</Text>
          </Card>
        )}
      </Spin>
    </div>
  );
};
