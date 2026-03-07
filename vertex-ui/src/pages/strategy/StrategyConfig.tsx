import { useState, useEffect } from 'react';
import {
  Table, Button, Space, message, Tag, Modal, Form, Select, Input, Switch,
  InputNumber, Card, Popconfirm, Divider, Tooltip,
} from 'antd';
import {
  PlusOutlined,
  DeleteOutlined,
  EditOutlined,
  ReloadOutlined,
  ExperimentOutlined,
  SwapOutlined,
  CopyOutlined,
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
import { exchangeAccountApi, ExchangeAccountVO, MarketType } from '../../api/trading';
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
          initialValue={20}
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
      return (
        <Form.Item
          name={[...prefix, 'params', 'deviationPct']}
          label={t('text.strategy.deviationPct')}
          initialValue={0.2}
          tooltip={t('text.strategy.deviationPctTip')}
        >
          <InputNumber min={0.05} max={5} step={0.05} style={{ width: 130 }} addonAfter="%" />
        </Form.Item>
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
    case 'SAR':
      return (
        <Space wrap>
          <Form.Item
            name={[...prefix, 'params', 'afStart']}
            label={t('text.strategy.afStart')}
            initialValue={0.02}
            rules={[{ required: true }]}
          >
            <InputNumber min={0.01} max={0.05} step={0.01} style={{ width: 100 }} />
          </Form.Item>
          <Form.Item
            name={[...prefix, 'params', 'afStep']}
            label={t('text.strategy.afStep')}
            initialValue={0.02}
            rules={[{ required: true }]}
          >
            <InputNumber min={0.01} max={0.04} step={0.01} style={{ width: 100 }} />
          </Form.Item>
          <Form.Item
            name={[...prefix, 'params', 'afMax']}
            label={t('text.strategy.afMax')}
            initialValue={0.2}
            rules={[{ required: true }]}
          >
            <InputNumber min={0.1} max={0.4} step={0.05} style={{ width: 100 }} />
          </Form.Item>
        </Space>
      );
    case 'ADX':
      return (
        <Space>
          <Form.Item
            name={[...prefix, 'params', 'period']}
            label={t('text.strategy.period')}
            initialValue={14}
            rules={[{ required: true }]}
          >
            <InputNumber min={7} max={30} style={{ width: 80 }} />
          </Form.Item>
          <Form.Item
            name={[...prefix, 'params', 'trendThreshold']}
            label={t('text.strategy.trendThreshold')}
            initialValue={25}
            rules={[{ required: true }]}
          >
            <InputNumber min={15} max={40} style={{ width: 80 }} />
          </Form.Item>
        </Space>
      );
    case 'SUPERTREND':
      return (
        <Space>
          <Form.Item
            name={[...prefix, 'params', 'period']}
            label={t('text.strategy.period')}
            initialValue={10}
            rules={[{ required: true }]}
          >
            <InputNumber min={5} max={30} style={{ width: 80 }} />
          </Form.Item>
          <Form.Item
            name={[...prefix, 'params', 'multiplier']}
            label={t('text.strategy.multiplier')}
            initialValue={3.0}
            rules={[{ required: true }]}
          >
            <InputNumber min={1.0} max={6.0} step={0.5} style={{ width: 80 }} />
          </Form.Item>
        </Space>
      );
    case 'VOL_CONFIRM':
      return (
        <Space wrap>
          <Form.Item
            name={[...prefix, 'params', 'period']}
            label={t('text.strategy.period')}
            initialValue={20}
            rules={[{ required: true }]}
          >
            <InputNumber min={5} max={50} style={{ width: 80 }} />
          </Form.Item>
          <Form.Item
            name={[...prefix, 'params', 'volMultiplier']}
            label={t('text.strategy.volMultiplier')}
            initialValue={1.5}
            rules={[{ required: true }]}
          >
            <InputNumber min={1.0} max={3.0} step={0.1} style={{ width: 80 }} />
          </Form.Item>
          <span style={{ color: '#888', fontSize: 12 }}>
            {t('text.strategy.volConfirmInfo')}
          </span>
        </Space>
      );
    case 'OBV':
      return (
        <Space wrap>
          <Form.Item
            name={[...prefix, 'params', 'signalPeriod']}
            label={t('text.strategy.signalPeriod')}
            initialValue={10}
            rules={[{ required: true }]}
          >
            <InputNumber min={3} max={30} style={{ width: 80 }} />
          </Form.Item>
          <span style={{ color: '#888', fontSize: 12 }}>
            {t('text.strategy.obvInfo')}
          </span>
        </Space>
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
  const [, setEditingRecord] = useState<StrategyVO | null>(null);
  const [form] = Form.useForm();

  // 指标配置联动
  const [indicatorTypes, setIndicatorTypes] = useState<(IndicatorType | undefined)[]>([]);

  // 自动交易
  const [autoTradeEnabled, setAutoTradeEnabled] = useState(false);
  const [positionSizingMode, setPositionSizingMode] = useState<string>('FIXED');
  const [accounts, setAccounts] = useState<ExchangeAccountVO[]>([]);
  const [selectedAccountMarketType, setSelectedAccountMarketType] = useState<MarketType | null>(null);

  // 回测
  const [backtestVisible, setBacktestVisible] = useState(false);
  const [backtestStrategy, setBacktestStrategy] = useState<StrategyVO | null>(null);

  const loadAccounts = async () => {
    try {
      const res = await exchangeAccountApi.list();
      if (res.code === 200) {
        setAccounts(res.data || []);
      }
    } catch {
      // silent
    }
  };

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

  useEffect(() => {
    loadAccounts();
  }, []);

  const handleCreate = () => {
    setEditingId(null);
    setEditingRecord(null);
    form.resetFields();
    form.setFieldsValue({ indicatorConfigs: [{}], autoTrade: 0, positionSizing: 'FIXED' });
    setIndicatorTypes([undefined]);
    setAutoTradeEnabled(false);
    setPositionSizingMode('FIXED');
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
      autoTrade: record.autoTrade ?? 0,
      minSignalStrength: record.minSignalStrength,
      tradeMode: record.tradeMode,
      executionMode: record.executionMode,
      accountId: record.accountId,
      positionSizing: record.positionSizing || 'FIXED',
      tradeQuantity: record.tradeQuantity,
      positionRatio: record.positionRatio,
      initialCapital: record.initialCapital,
      stopLossPct: record.stopLossPct,
      takeProfitPct: record.takeProfitPct,
      feeRate: record.feeRate,
      leverage: record.leverage || 1,
      marginType: record.marginType || 'ISOLATED',
      atrStopMultiplier: record.atrStopMultiplier,
      atrTakeProfitMultiplier: record.atrTakeProfitMultiplier,
      initialStopMultiplier: record.initialStopMultiplier,
      breakevenActivationMultiplier: record.breakevenActivationMultiplier,
      trailingActivationMultiplier: record.trailingActivationMultiplier,
      trailingDistanceMultiplier: record.trailingDistanceMultiplier,
      atrInterval: record.atrInterval ?? undefined,
    });
    setIndicatorTypes(record.indicatorConfigs.map((c) => c.indicatorType));
    setAutoTradeEnabled(record.autoTrade === 1);
    setPositionSizingMode(record.positionSizing || 'FIXED');
    // 更新已选账户的市场类型
    if (record.accountId) {
      const acct = accounts.find((a) => a.id === record.accountId);
      setSelectedAccountMarketType(acct?.marketType || null);
    } else {
      setSelectedAccountMarketType(null);
    }
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

  const handleCopy = async (record: StrategyVO) => {
    try {
      const res = await strategyApi.copy(record.id);
      if (res.data) {
        // 复制成功后刷新列表，再拉取新策略详情并打开编辑弹窗
        await loadData();
        const detail = await strategyApi.getById(res.data);
        if (detail.data) {
          handleEdit(detail.data);
          message.success(t('message.strategy.copySuccess'));
        }
      }
    } catch {
      message.error(t('message.strategy.copyFailed'));
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
        // 可清空的数字字段：用户清空输入后值为 undefined，JSON.stringify 会将其忽略，
        // 导致后端收不到 null 从而无法清空数据库中的旧值。
        // 这里显式将 undefined 转为 null，确保后端能识别用户的"清空"意图。
        const CLEARABLE_FIELDS = [
          'minSignalStrength', 'stopLossPct', 'takeProfitPct',
          'feeRate', 'atrStopMultiplier', 'atrTakeProfitMultiplier',
          'initialStopMultiplier', 'breakevenActivationMultiplier',
          'trailingActivationMultiplier', 'trailingDistanceMultiplier',
          'atrInterval',
        ] as const;
        const payload: Record<string, unknown> = { id: editingId, ...values };
        CLEARABLE_FIELDS.forEach((key) => {
          if (payload[key] === undefined) payload[key] = null;
        });
        await strategyApi.update(payload as unknown as Parameters<typeof strategyApi.update>[0]);
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
      title: t('text.trading.autoTrade'),
      key: 'autoTrade',
      width: 100,
      render: (_: unknown, record: StrategyVO) => (
        record.autoTrade === 1 ? (
          <Tooltip title={`${record.tradeMode || '-'} / ${record.executionMode || '-'}`}>
            <Tag color="green" icon={<SwapOutlined />}>{t('common.enabled')}</Tag>
          </Tooltip>
        ) : (
          <Tag>{t('common.disabled')}</Tag>
        )
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
      width: 300,
      render: (_: unknown, record: StrategyVO) => (
        <Space>
          <Button type="link" icon={<EditOutlined />} onClick={() => handleEdit(record)}>
            {t('common.edit')}
          </Button>
          <Button type="link" icon={<CopyOutlined />} onClick={() => handleCopy(record)}>
            {t('common.copy')}
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

                      <Form.Item
                        name={[field.name, 'penaltyWeight']}
                        label={t('text.strategy.penaltyWeight')}
                        initialValue={0}
                        tooltip={t('text.strategy.penaltyWeightTip')}
                      >
                        <InputNumber min={0} max={100} style={{ width: 80 }} />
                      </Form.Item>

                      <Form.Item
                        name={[field.name, 'interval']}
                        label={t('text.strategy.indicatorInterval')}
                      >
                        <Select
                          allowClear
                          placeholder={t('text.strategy.useDefault')}
                          style={{ width: 120 }}
                          options={INTERVAL_OPTIONS}
                        />
                      </Form.Item>

                      <IndicatorParamsFields type={indicatorTypes[index]} prefix={[field.name]} />
                    </Space>
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

          <Divider>{t('text.trading.title')}</Divider>

          <Form.Item
            name="autoTrade"
            label={t('text.trading.autoTrade')}
            valuePropName="checked"
            getValueFromEvent={(checked: boolean) => checked ? 1 : 0}
            getValueProps={(value: number) => ({ checked: value === 1 })}
          >
            <Switch
              checkedChildren={t('common.enabled')}
              unCheckedChildren={t('common.disabled')}
              onChange={(checked) => setAutoTradeEnabled(checked)}
            />
          </Form.Item>

          <Form.Item
            name="minSignalStrength"
            label={t('text.trading.minSignalStrength')}
            initialValue={60}
            tooltip={t('text.trading.minSignalStrengthTip')}
          >
            <InputNumber
              min={0}
              max={100}
              style={{ width: 120 }}
              addonAfter="%"
            />
          </Form.Item>

          {autoTradeEnabled && (
            <>
              <Space style={{ width: '100%' }} size="large" wrap>
                <Form.Item
                  name="tradeMode"
                  label={t('text.trading.tradeMode')}
                  rules={[{ required: true, message: t('placeholder.common.select') }]}
                  initialValue="AUTO"
                >
                  <Select style={{ width: 160 }}>
                    <Select.Option value="AUTO">{t('text.trading.tradeModeAuto')}</Select.Option>
                    <Select.Option value="MANUAL">{t('text.trading.tradeModeManual')}</Select.Option>
                  </Select>
                </Form.Item>

                <Form.Item
                  name="executionMode"
                  label={t('text.trading.executionMode')}
                  rules={[{ required: true, message: t('placeholder.common.select') }]}
                  initialValue="PAPER"
                >
                  <Select style={{ width: 160 }}>
                    <Select.Option value="PAPER">{t('text.trading.executionPaper')}</Select.Option>
                    <Select.Option value="LIVE">{t('text.trading.executionLive')}</Select.Option>
                  </Select>
                </Form.Item>

                <Form.Item
                  name="accountId"
                  label={t('text.trading.account')}
                >
                  <Select
                    style={{ width: 200 }}
                    allowClear
                    placeholder={t('placeholder.common.select')}
                    onChange={(val: string) => {
                      const acct = accounts.find((a) => a.id === val);
                      setSelectedAccountMarketType(acct?.marketType || null);
                    }}
                  >
                    {accounts.map((acc) => (
                      <Select.Option key={acc.id} value={acc.id}>
                        {acc.name} ({acc.exchange}
                        {acc.marketType && acc.marketType !== 'SPOT' ? ` · ${acc.marketType}` : ''})
                      </Select.Option>
                    ))}
                  </Select>
                </Form.Item>

                {/* 合约参数（仅合约账户显示）*/}
                {selectedAccountMarketType && selectedAccountMarketType !== 'SPOT' && (
                  <>
                    <Form.Item name="leverage" label="杠杆倍数" initialValue={1}>
                      <InputNumber min={1} max={125} step={1} style={{ width: 120 }} addonAfter="x" />
                    </Form.Item>
                    <Form.Item name="marginType" label="保证金模式" initialValue="ISOLATED">
                      <Select style={{ width: 120 }}>
                        <Select.Option value="ISOLATED">逐仓</Select.Option>
                        <Select.Option value="CROSS">全仓</Select.Option>
                      </Select>
                    </Form.Item>
                  </>
                )}
              </Space>

              <Space style={{ width: '100%' }} size="large" wrap>
                <Form.Item
                  name="positionSizing"
                  label={t('text.trading.positionSizing')}
                  initialValue="FIXED"
                >
                  <Select style={{ width: 160 }} onChange={(val) => setPositionSizingMode(val)}>
                    <Select.Option value="FIXED">{t('text.trading.positionSizingFixed')}</Select.Option>
                    <Select.Option value="PERCENT">{t('text.trading.positionSizingPercent')}</Select.Option>
                  </Select>
                </Form.Item>

                {positionSizingMode === 'FIXED' ? (
                  <Form.Item
                    name="tradeQuantity"
                    label={t('text.trading.tradeQuantity')}
                    rules={[{ required: positionSizingMode === 'FIXED', message: t('text.trading.tradeQuantityPlaceholder') }]}
                  >
                    <InputNumber min={0} step={0.001} style={{ width: 160 }} placeholder="0.001" />
                  </Form.Item>
                ) : (
                  <>
                    <Form.Item
                      name="positionRatio"
                      label={t('text.trading.positionRatio')}
                      tooltip={t('text.trading.positionRatioTip')}
                      initialValue={1.0}
                    >
                      <InputNumber min={0.01} max={1} step={0.1} style={{ width: 160 }} addonAfter="x" />
                    </Form.Item>
                    <Form.Item
                      name="initialCapital"
                      label={t('text.trading.initialCapital')}
                      tooltip={t('text.trading.initialCapitalTip')}
                      initialValue={10000}
                    >
                      <InputNumber min={0} step={100} style={{ width: 160 }} addonAfter="USDT" />
                    </Form.Item>
                  </>
                )}
              </Space>

              <Space style={{ width: '100%' }} size="large" wrap>
                <Form.Item
                  name="stopLossPct"
                  label={t('text.trading.stopLoss')}
                  tooltip={t('text.trading.stopLossTip')}
                >
                  <InputNumber min={0} max={100} step={0.5} style={{ width: 140 }} addonAfter="%" />
                </Form.Item>

                <Form.Item
                  name="atrStopMultiplier"
                  label={t('text.trading.atrStopMultiplier')}
                  tooltip={t('text.trading.atrStopMultiplierTip')}
                >
                  <InputNumber min={0.1} max={20} step={0.1} style={{ width: 140 }} addonAfter="× ATR" placeholder="2.0" />
                </Form.Item>

                <Form.Item
                  name="takeProfitPct"
                  label={t('text.trading.takeProfit')}
                  tooltip={t('text.trading.takeProfitTip')}
                >
                  <InputNumber min={0} max={1000} step={0.5} style={{ width: 140 }} addonAfter="%" />
                </Form.Item>

                <Form.Item
                  name="atrTakeProfitMultiplier"
                  label={t('text.trading.atrTakeProfitMultiplier')}
                  tooltip={t('text.trading.atrTakeProfitMultiplierTip')}
                >
                  <InputNumber min={0.1} max={50} step={0.1} style={{ width: 140 }} addonAfter="× ATR" placeholder="3.0" />
                </Form.Item>

                <Form.Item
                  name="feeRate"
                  label={t('text.trading.feeRate')}
                  tooltip={t('text.trading.feeRateTip')}
                >
                  <InputNumber min={0} max={0.1} step={0.0001} style={{ width: 160 }} placeholder="0.001" />
                </Form.Item>
              </Space>

              <Divider orientation="left" style={{ marginTop: 8 }}>{t('text.trading.trailingStopTitle')}</Divider>
              <Space size="large" wrap>
                <Form.Item
                  name="initialStopMultiplier"
                  label={t('text.trading.initialStopMultiplier')}
                  tooltip={t('text.trading.initialStopMultiplierTip')}
                >
                  <InputNumber min={0.1} max={20} step={0.1} style={{ width: 150 }} addonAfter="× ATR" placeholder="3.5" />
                </Form.Item>

                <Form.Item
                  name="breakevenActivationMultiplier"
                  label={t('text.trading.breakevenActivationMultiplier')}
                  tooltip={t('text.trading.breakevenActivationMultiplierTip')}
                >
                  <InputNumber min={0.1} max={20} step={0.1} style={{ width: 150 }} addonAfter="× ATR" placeholder="1.8" />
                </Form.Item>

                <Form.Item
                  name="trailingActivationMultiplier"
                  label={t('text.trading.trailingActivationMultiplier')}
                  tooltip={t('text.trading.trailingActivationMultiplierTip')}
                >
                  <InputNumber min={0.1} max={20} step={0.1} style={{ width: 150 }} addonAfter="× ATR" placeholder="2.5" />
                </Form.Item>

                <Form.Item
                  name="trailingDistanceMultiplier"
                  label={t('text.trading.trailingDistanceMultiplier')}
                  tooltip={t('text.trading.trailingDistanceMultiplierTip')}
                >
                  <InputNumber min={0.1} max={20} step={0.1} style={{ width: 150 }} addonAfter="× ATR" placeholder="2.0" />
                </Form.Item>
                <Form.Item
                  name="atrInterval"
                  label={t('text.trading.atrInterval')}
                  tooltip={t('text.trading.atrIntervalTip')}
                >
                  <Select
                    allowClear
                    style={{ width: 120 }}
                    placeholder={t('placeholder.strategy.interval')}
                    options={INTERVAL_OPTIONS}
                  />
                </Form.Item>
              </Space>
            </>
          )}
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
