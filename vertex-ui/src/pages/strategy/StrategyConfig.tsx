import { useState, useEffect } from 'react';
import {
  Table, Button, Space, message, Tag, Modal, Form, Select, Input, Switch,
  InputNumber, Card, Popconfirm, Divider,
} from 'antd';
import {
  PlusOutlined,
  DeleteOutlined,
  EditOutlined,
  ReloadOutlined,
  ExperimentOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import {
  strategyApi,
  StrategyVO,
  StrategyCreateDTO,
  IndicatorType,
  INDICATOR_TYPE_LABELS,
} from '../../api/strategy';
import { KLINE_INTERVAL_LABELS, KLineInterval } from '../../api/quote';
import { BacktestPanel } from './BacktestPanel';

const INTERVAL_OPTIONS = Object.entries(KLINE_INTERVAL_LABELS).map(([value, label]) => ({
  value,
  label,
}));

const INDICATOR_OPTIONS = Object.entries(INDICATOR_TYPE_LABELS).map(([value, label]) => ({
  value,
  label,
}));

/** 根据指标类型渲染参数字段 */
const IndicatorParamsFields = ({
  type,
  prefix,
}: {
  type: IndicatorType | undefined;
  prefix: (number | string)[];
}) => {
  const { t } = useTranslation();

  if (!type) return null;

  switch (type) {
    case 'MA':
    case 'EMA':
      return (
        <Form.Item
          name={[...prefix, 'params', 'period']}
          label={t('text.strategy.period')}
          initialValue={type === 'RSI' ? 14 : 20}
          rules={[{ required: true }]}
        >
          <InputNumber min={2} max={500} style={{ width: 120 }} />
        </Form.Item>
      );
    case 'RSI':
      return (
        <Form.Item
          name={[...prefix, 'params', 'period']}
          label={t('text.strategy.period')}
          initialValue={14}
          rules={[{ required: true }]}
        >
          <InputNumber min={2} max={100} style={{ width: 120 }} />
        </Form.Item>
      );
    case 'MACD':
      return (
        <Space>
          <Form.Item
            name={[...prefix, 'params', 'fast']}
            label={t('text.strategy.fast')}
            initialValue={12}
            rules={[{ required: true }]}
          >
            <InputNumber min={2} max={100} style={{ width: 80 }} />
          </Form.Item>
          <Form.Item
            name={[...prefix, 'params', 'slow']}
            label={t('text.strategy.slow')}
            initialValue={26}
            rules={[{ required: true }]}
          >
            <InputNumber min={2} max={100} style={{ width: 80 }} />
          </Form.Item>
          <Form.Item
            name={[...prefix, 'params', 'signal']}
            label={t('text.strategy.signalLine')}
            initialValue={9}
            rules={[{ required: true }]}
          >
            <InputNumber min={2} max={50} style={{ width: 80 }} />
          </Form.Item>
        </Space>
      );
    case 'BOLL':
      return (
        <Space>
          <Form.Item
            name={[...prefix, 'params', 'period']}
            label={t('text.strategy.period')}
            initialValue={20}
            rules={[{ required: true }]}
          >
            <InputNumber min={5} max={100} style={{ width: 80 }} />
          </Form.Item>
          <Form.Item
            name={[...prefix, 'params', 'multiplier']}
            label={t('text.strategy.multiplier')}
            initialValue={2.0}
            rules={[{ required: true }]}
          >
            <InputNumber min={0.5} max={5} step={0.1} style={{ width: 80 }} />
          </Form.Item>
        </Space>
      );
    case 'KDJ':
      return (
        <Space>
          <Form.Item
            name={[...prefix, 'params', 'rsvPeriod']}
            label={t('text.strategy.rsvPeriod')}
            initialValue={9}
            rules={[{ required: true }]}
          >
            <InputNumber min={2} max={50} style={{ width: 80 }} />
          </Form.Item>
          <Form.Item
            name={[...prefix, 'params', 'kPeriod']}
            label={t('text.strategy.kLine')}
            initialValue={3}
            rules={[{ required: true }]}
          >
            <InputNumber min={1} max={20} style={{ width: 80 }} />
          </Form.Item>
          <Form.Item
            name={[...prefix, 'params', 'dPeriod']}
            label={t('text.strategy.dLine')}
            initialValue={3}
            rules={[{ required: true }]}
          >
            <InputNumber min={1} max={20} style={{ width: 80 }} />
          </Form.Item>
        </Space>
      );
    case 'ATR':
      return (
        <Form.Item
          name={[...prefix, 'params', 'period']}
          label={t('text.strategy.period')}
          initialValue={14}
          rules={[{ required: true }]}
        >
          <InputNumber min={2} max={100} style={{ width: 120 }} />
        </Form.Item>
      );
    case 'VWAP':
      // VWAP 无需用户配置参数，使用全部窗口K线计算
      return (
        <span style={{ color: '#888', fontSize: 12 }}>
          {t('text.strategy.vwapNoParams')}
        </span>
      );
    case 'STOCH_RSI':
      return (
        <Space wrap>
          <Form.Item
            name={[...prefix, 'params', 'rsiPeriod']}
            label={t('text.strategy.rsiPeriod')}
            initialValue={14}
            rules={[{ required: true }]}
          >
            <InputNumber min={2} max={50} style={{ width: 80 }} />
          </Form.Item>
          <Form.Item
            name={[...prefix, 'params', 'stochPeriod']}
            label={t('text.strategy.stochPeriod')}
            initialValue={14}
            rules={[{ required: true }]}
          >
            <InputNumber min={2} max={50} style={{ width: 80 }} />
          </Form.Item>
          <Form.Item
            name={[...prefix, 'params', 'kSmooth']}
            label={t('text.strategy.kSmooth')}
            initialValue={3}
            rules={[{ required: true }]}
          >
            <InputNumber min={1} max={20} style={{ width: 80 }} />
          </Form.Item>
          <Form.Item
            name={[...prefix, 'params', 'dSmooth']}
            label={t('text.strategy.dSmooth')}
            initialValue={3}
            rules={[{ required: true }]}
          >
            <InputNumber min={1} max={20} style={{ width: 80 }} />
          </Form.Item>
        </Space>
      );
    case 'WR':
      return (
        <Form.Item
          name={[...prefix, 'params', 'period']}
          label={t('text.strategy.period')}
          initialValue={14}
          rules={[{ required: true }]}
        >
          <InputNumber min={2} max={100} style={{ width: 120 }} />
        </Form.Item>
      );
    default:
      return null;
  }
};

export const StrategyConfig = () => {
  const { t } = useTranslation();
  const [strategies, setStrategies] = useState<StrategyVO[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(10);

  // 弹窗
  const [modalVisible, setModalVisible] = useState(false);
  const [modalLoading, setModalLoading] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editingRecord, setEditingRecord] = useState<StrategyVO | null>(null);
  const [form] = Form.useForm();

  // 指标配置联动
  const [indicatorTypes, setIndicatorTypes] = useState<(IndicatorType | undefined)[]>([]);

  // 回测
  const [backtestVisible, setBacktestVisible] = useState(false);
  const [backtestStrategy, setBacktestStrategy] = useState<StrategyVO | null>(null);

  const loadData = async () => {
    setLoading(true);
    try {
      const response = await strategyApi.page({ pageNum, pageSize });
      if (response.code === 200) {
        setStrategies(response.data.records);
        setTotal(response.data.total);
      }
    } catch {
      message.error(t('message.strategy.loadFailed'));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadData();
  }, [pageNum, pageSize]);

  const handleCreate = () => {
    setEditingId(null);
    setEditingRecord(null);
    form.resetFields();
    form.setFieldsValue({ indicatorConfigs: [{}] });
    setIndicatorTypes([undefined]);
    setModalVisible(true);
  };

  const handleEdit = (record: StrategyVO) => {
    setEditingId(record.id);
    setEditingRecord(record);
    form.resetFields();
    form.setFieldsValue({
      name: record.name,
      description: record.description,
      exchange: record.exchange,
      symbol: record.symbol,
      interval: record.interval,
      indicatorConfigs: record.indicatorConfigs,
    });
    setIndicatorTypes(record.indicatorConfigs.map((c) => c.indicatorType));
    setModalVisible(true);
  };

  const handleDelete = async (id: string) => {
    try {
      await strategyApi.delete(id);
      message.success(t('message.strategy.deleteSuccess'));
      loadData();
    } catch {
      message.error(t('message.common.deleteFailed'));
    }
  };

  const handleToggleEnabled = async (record: StrategyVO) => {
    try {
      if (record.enabled === 1) {
        await strategyApi.disable(record.id);
        message.success(t('message.strategy.disableSuccess'));
      } else {
        await strategyApi.enable(record.id);
        message.success(t('message.strategy.enableAndSubscribed'));
      }
      loadData();
    } catch {
      message.error(t('message.strategy.loadFailed'));
    }
  };

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      setModalLoading(true);

      if (editingId) {
        await strategyApi.update({ id: editingId, ...values });
        message.success(t('message.strategy.updateSuccess'));
      } else {
        await strategyApi.create(values as StrategyCreateDTO);
        message.success(t('message.strategy.createSuccess'));
      }
      setModalVisible(false);
      loadData();
    } catch {
      // validation error
    } finally {
      setModalLoading(false);
    }
  };

  const columns = [
    {
      title: t('text.strategy.name'),
      dataIndex: 'name',
      key: 'name',
      render: (text: string) => <span style={{ fontWeight: 600 }}>{text}</span>,
    },
    {
      title: t('text.strategy.exchange'),
      dataIndex: 'exchange',
      key: 'exchange',
      render: (text: string) => <span style={{ textTransform: 'uppercase' as const }}>{text}</span>,
    },
    {
      title: t('text.strategy.symbol'),
      dataIndex: 'symbol',
      key: 'symbol',
    },
    {
      title: t('text.strategy.interval'),
      dataIndex: 'interval',
      key: 'interval',
      render: (val: KLineInterval) => KLINE_INTERVAL_LABELS[val] || val,
    },
    {
      title: t('text.strategy.indicators'),
      key: 'indicators',
      render: (_: unknown, record: StrategyVO) => (
        <Space>
          {record.indicatorConfigs?.map((c, i) => (
            <Tag key={i} color="blue">{c.indicatorType}</Tag>
          ))}
        </Space>
      ),
    },
    {
      title: t('text.strategy.enabled'),
      key: 'enabled',
      render: (_: unknown, record: StrategyVO) => (
        <Switch
          checked={record.enabled === 1}
          onChange={() => handleToggleEnabled(record)}
          checkedChildren={t('common.enabled')}
          unCheckedChildren={t('common.disabled')}
        />
      ),
    },
    {
      title: t('common.createTime'),
      dataIndex: 'createTime',
      key: 'createTime',
      width: 180,
    },
    {
      title: t('common.operation'),
      key: 'action',
      width: 220,
      render: (_: unknown, record: StrategyVO) => (
        <Space>
          <Button type="link" icon={<EditOutlined />} onClick={() => handleEdit(record)}>
            {t('common.edit')}
          </Button>
          <Button
            type="link"
            icon={<ExperimentOutlined />}
            onClick={() => { setBacktestStrategy(record); setBacktestVisible(true); }}
          >
            {t('text.strategy.backtest')}
          </Button>
          <Popconfirm
            title={t('message.strategy.deleteConfirm')}
            onConfirm={() => handleDelete(record.id)}
          >
            <Button type="link" danger icon={<DeleteOutlined />}>
              {t('common.delete')}
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h2 style={{ margin: 0, fontSize: 20, fontWeight: 600 }}>{t('text.strategy.configTitle')}</h2>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={loadData}>
            {t('text.quote.refresh')}
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={handleCreate}>
            {t('common.add')}
          </Button>
        </Space>
      </div>

      <Table
        columns={columns}
        dataSource={strategies}
        loading={loading}
        rowKey="id"
        pagination={{
          current: pageNum,
          pageSize,
          total,
          showTotal: (t) => `共 ${t} 条`,
          onChange: (p, s) => { setPageNum(p); setPageSize(s); },
        }}
      />

      {/* 创建/编辑策略弹窗 */}
      <Modal
        title={editingId ? t('common.edit') + ' ' + t('text.strategy.name') : t('common.add') + ' ' + t('text.strategy.name')}
        open={modalVisible}
        onOk={handleSubmit}
        confirmLoading={modalLoading}
        onCancel={() => { setModalVisible(false); setEditingRecord(null); }}
        width={720}
      >
        <Form form={form} layout="vertical">
          <Form.Item
            name="name"
            label={t('text.strategy.name')}
            rules={[{ required: true, message: t('placeholder.strategy.name') }]}
          >
            <Input placeholder={t('placeholder.strategy.name')} />
          </Form.Item>

          <Form.Item name="description" label={t('text.strategy.description')}>
            <Input.TextArea placeholder={t('placeholder.strategy.description')} rows={2} />
          </Form.Item>

          <Space style={{ width: '100%' }} size="large">
            <Form.Item
              name="exchange"
              label={t('text.strategy.exchange')}
              rules={[{ required: true, message: t('placeholder.strategy.exchange') }]}
            >
              <Select placeholder={t('placeholder.strategy.exchange')} style={{ width: 200 }}>
                <Select.Option value="binance">Binance</Select.Option>
                <Select.Option value="okx">OKX</Select.Option>
              </Select>
            </Form.Item>

            <Form.Item
              name="symbol"
              label={t('text.strategy.symbol')}
              rules={[{ required: true, message: t('placeholder.strategy.symbol') }]}
            >
              <Input placeholder={t('placeholder.strategy.symbol')} style={{ width: 200 }} />
            </Form.Item>

            <Form.Item
              name="interval"
              label={t('text.strategy.interval')}
              rules={[{ required: true, message: t('placeholder.strategy.interval') }]}
            >
              <Select placeholder={t('placeholder.strategy.interval')} style={{ width: 120 }} options={INTERVAL_OPTIONS} />
            </Form.Item>
          </Space>

          <Divider>{t('text.strategy.indicators')}</Divider>

          <Form.List name="indicatorConfigs">
            {(fields, { add, remove }) => (
              <>
                {fields.map((field, index) => (
                  <Card
                    key={field.key}
                    size="small"
                    style={{ marginBottom: 12 }}
                    extra={
                      fields.length > 1 && (
                        <Button
                          type="link"
                          danger
                          size="small"
                          onClick={() => {
                            remove(field.name);
                            setIndicatorTypes((prev) => prev.filter((_, i) => i !== index));
                          }}
                        >
                          {t('common.delete')}
                        </Button>
                      )
                    }
                  >
                    <Space align="start" wrap>
                      <Form.Item
                        name={[field.name, 'indicatorType']}
                        label={t('text.strategy.indicatorType')}
                        rules={[{ required: true, message: t('placeholder.common.select') }]}
                      >
                        <Select
                          placeholder={t('placeholder.common.select')}
                          style={{ width: 220 }}
                          options={INDICATOR_OPTIONS}
                          onChange={(val: IndicatorType) => {
                            setIndicatorTypes((prev) => {
                              const next = [...prev];
                              next[index] = val;
                              return next;
                            });
                          }}
                        />
                      </Form.Item>

                      <Form.Item
                        name={[field.name, 'weight']}
                        label={t('text.strategy.weight')}
                        initialValue={50}
                        rules={[{ required: true }]}
                      >
                        <InputNumber min={1} max={100} style={{ width: 80 }} />
                      </Form.Item>
                    </Space>

                    <IndicatorParamsFields type={indicatorTypes[index]} prefix={[field.name]} />
                  </Card>
                ))}

                <Button
                  type="dashed"
                  onClick={() => {
                    add({});
                    setIndicatorTypes((prev) => [...prev, undefined]);
                  }}
                  block
                  icon={<PlusOutlined />}
                >
                  {t('text.strategy.addIndicator')}
                </Button>
              </>
            )}
          </Form.List>
        </Form>
      </Modal>

      {/* 回测面板 */}
      {backtestStrategy && (
        <BacktestPanel
          strategyId={backtestStrategy.id}
          strategyName={backtestStrategy.name}
          visible={backtestVisible}
          onClose={() => setBacktestVisible(false)}
        />
      )}
    </div>
  );
};
