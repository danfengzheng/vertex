import { useEffect, useState } from 'react';
import {
  Card, Form, Input, InputNumber, Select, Switch, Button, Space, Row, Col,
  Alert, Divider, message, Spin,
} from 'antd';
import { ArrowLeftOutlined, SaveOutlined } from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { mmGridApi, GridStrategyVO } from '../../api/mmGrid';
import { exchangeAccountApi, ExchangeAccountVO } from '../../api/trading';
import GridPreviewPanel from './GridPreviewPanel';

/**
 * 网格策略创建 / 编辑表单
 */
export const GridStrategyForm = () => {
  const { t } = useTranslation();
  const nav = useNavigate();
  const { id } = useParams<{ id: string }>();
  const editing = id && id !== 'new';
  const [form] = Form.useForm<GridStrategyVO>();
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [accounts, setAccounts] = useState<ExchangeAccountVO[]>([]);

  // 拉账户列表供 accountId Select 使用
  useEffect(() => {
    exchangeAccountApi.list().then(r => {
      if (r.code === 200 && r.data) setAccounts(r.data);
    }).catch(() => { /* 失败时留空，用户看到空下拉会去创建账户 */ });
  }, []);

  const load = async () => {
    if (!editing) return;
    setLoading(true);
    try {
      const res = await mmGridApi.get(id!);
      if (res.code === 200 && res.data) form.setFieldsValue(res.data);
    } finally {
      setLoading(false);
    }
  };
  useEffect(() => { load(); /* eslint-disable-next-line */ }, [id]);

  const onSubmit = async () => {
    let values;
    try {
      values = await form.validateFields();
    } catch (e) {
      // 校验失败 —— 明确告知用户哪些必填字段没填，自动滚到第一个错误字段
      const err = e as { errorFields?: Array<{ name: (string | number)[]; errors: string[] }> };
      const errorFields = err.errorFields ?? [];
      if (errorFields.length > 0) {
        const first = errorFields[0];
        const fieldName = Array.isArray(first.name) ? first.name.join('.') : String(first.name);
        message.error(`表单校验失败：${fieldName} - ${first.errors[0] ?? '必填'}（共 ${errorFields.length} 处错误）`);
        try {
          form.scrollToField(first.name, { behavior: 'smooth', block: 'center' });
        } catch { /* ignore */ }
      } else {
        message.error('表单校验失败');
      }
      return;
    }

    setSaving(true);
    try {
      if (editing) {
        const res = await mmGridApi.update(id!, values);
        if (res.code === 200) {
          message.success(t('mm.updateOk', { defaultValue: '已更新' }));
          nav('/mm/grid');
        } else {
          message.error(res.message || 'update failed');
        }
      } else {
        const res = await mmGridApi.create(values);
        if (res.code === 200) {
          message.success(t('mm.createOk', { defaultValue: '已创建' }));
          nav('/mm/grid');
        } else {
          message.error(res.message || 'create failed');
        }
      }
    } catch (e) {
      const err = e as { message?: string; response?: { data?: { message?: string } } };
      const msg = err.response?.data?.message || err.message || 'save failed';
      message.error(`保存失败：${msg}`);
      console.error('[GridStrategyForm] save error:', e);
    } finally {
      setSaving(false);
    }
  };

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between' }}>
        <Space>
          <Button icon={<ArrowLeftOutlined />} onClick={() => nav('/mm/grid')}>返回</Button>
          <h2 style={{ margin: 0 }}>{editing ? t('mm.editTitle', { defaultValue: '编辑策略' }) : t('mm.newTitle', { defaultValue: '新建策略' })}</h2>
        </Space>
        <Button type="primary" icon={<SaveOutlined />} onClick={onSubmit} loading={saving}>
          保存
        </Button>
      </div>

      <Spin spinning={loading}>
        <Card>
          <Alert type="info" showIcon style={{ marginBottom: 16 }}
                 message="Maker-only + 5 层保护 + 事件驱动补单"
                 description="所有下单硬编码 GTX（0 taker fee），触发 5 层保护体系（预警/非对称/主动减仓/分侧停机/账户熔断）+ 私有 WebSocket 事件驱动。参数保守化启用，可后期放宽。" />

          <Form form={form} layout="vertical" initialValues={{
            exchange: 'binance-usdcm',
            executionMode: 'PAPER',
            side: 'BOTH',
            gridMode: 'ARITHMETIC',
            paperQueueLossFactor: 0.5,
            paperGtxRejectRate: 0.05,
            paperSmartPricingEnabled: 1,
            paperMinLiquidityQty: 2.0,
            hedgeMode: 1,
            longSideEnabled: 1,
            shortSideEnabled: 1,
            // V1 默认：自适应步长 + 趋势保护 关闭（V2 特性，V1 用户显式启用才生效）
            adaptiveStepEnabled: 0,
            trendProtectionEnabled: 0,
            windowSizePerSide: 5,
            // baseStepPct 不设默认值：V1 会根据 lower/upper/gridCount/gridMode 自动推导
            minimumStepSafetyMultiplier: 5.0,
            trendLayer1Interval: '5m',
            trendLayer2Interval: '15m',
            trendLayer3Interval: '1h',
            trendLayer4Interval: '4h',
            trendLayer2AdxEnter: 25, trendLayer2AdxExit: 20,
            trendLayer3AdxEnter: 30, trendLayer3AdxExit: 22,
            trendLayer4AdxEnter: 35,
            longReducePnlPct: 1.0, shortReducePnlPct: 1.0,
            longStopPnlPct: 3.0, shortStopPnlPct: 3.0,
            accountEmergencyPnlPct: 5.0,
            riskAutoStopEnabled: 0,   // 默认关，避免隐藏坑
            ladderPctOfPosition: 30, ladderStepCount: 5, ladderMinReservePct: 0.05,
            reshuffleCooldownSec: 300,
            emergencyTakerFallbackEnabled: 1,
            emergencyTakerLossThreshold: 0.03,
            leverage: 3,
            marginType: 'ISOLATED',
          }}>
            {/* 执行模式 —— 最重要，放最前面 */}
            <Divider titlePlacement="start">执行模式</Divider>
            <Alert type="warning" showIcon style={{ marginBottom: 16 }}
                   message="首次一定选 PAPER，跑 3-5 天观察表现达标后再切 LIVE"
                   description="PAPER 用真实市场数据模拟成交（无风险）；LIVE 真实下单。任何时候都可以在此切换（重启策略生效）。" />
            <Row gutter={16}>
              <Col xs={24} md={8}>
                <Form.Item name="executionMode" label="执行模式" rules={[{ required: true }]}>
                  <Select options={[
                    { value: 'PAPER', label: '📝 PAPER (模拟成交，无风险)' },
                    { value: 'LIVE', label: '💰 LIVE (真实下单)' },
                  ]} />
                </Form.Item>
              </Col>
              <Col xs={24} md={8}>
                <Form.Item name="paperSmartPricingEnabled" label="智能挂单价"
                           tooltip="根据 10 档深度自动避开薄簿位（PAPER + LIVE 都可用）"
                           valuePropName="checked"
                           getValueFromEvent={(v) => (v ? 1 : 0)} getValueProps={(v) => ({ checked: v === 1 })}>
                  <Switch />
                </Form.Item>
              </Col>
              <Col xs={24} md={8}>
                <Form.Item name="paperMinLiquidityQty" label="薄簿位阈值"
                           tooltip="某档排队量 < 此值视为薄，会跳到旁边有排队的档。ETH 建议 2-5，BTC 建议 0.5">
                  <InputNumber min={0.01} step={0.5} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
            </Row>

            <Form.Item shouldUpdate={(prev, cur) => prev.executionMode !== cur.executionMode} noStyle>
              {({ getFieldValue }) => getFieldValue('executionMode') === 'PAPER' && (
                <>
                  <Row gutter={16}>
                    <Col xs={24} md={8}>
                      <Form.Item name="paperQueueLossFactor" label="模拟排队损失系数"
                                 tooltip="仅填充 real trade qty 的 X%（1.0=乐观，0.5=中性，0.3=悲观）">
                        <InputNumber min={0.1} max={1.0} step={0.05} style={{ width: '100%' }} />
                      </Form.Item>
                    </Col>
                    <Col xs={24} md={8}>
                      <Form.Item name="paperGtxRejectRate" label="模拟 GTX 拒绝率"
                                 tooltip="随机拒绝这个比例的挂单（模拟实盘 spread 抖动被拒）">
                        <InputNumber min={0} max={0.5} step={0.01} style={{ width: '100%' }} />
                      </Form.Item>
                    </Col>
                  </Row>
                </>
              )}
            </Form.Item>

            {/* 基础 */}
            <Divider titlePlacement="start">基础配置</Divider>
            <Row gutter={16}>
              <Col xs={24} md={8}>
                <Form.Item name="name" label="策略名称" rules={[{ required: true }]}>
                  <Input placeholder="ETH USDC Grid #1" />
                </Form.Item>
              </Col>
              <Col xs={24} md={8}>
                <Form.Item name="exchange" label="交易所" rules={[{ required: true }]}>
                  <Select options={[{ value: 'binance-usdcm', label: 'Binance USDC-M' }]} />
                </Form.Item>
              </Col>
              <Col xs={24} md={8}>
                <Form.Item name="symbol" label="交易对" rules={[{ required: true }]}>
                  <Input placeholder="ETHUSDC" />
                </Form.Item>
              </Col>
              <Col xs={24} md={8}>
                <Form.Item name="accountId" label="交易账户" rules={[{ required: true }]}
                           tooltip="需要在'交易账户'页面先录入 API 凭证。PAPER 模式可用不存在的 ID">
                  <Select
                    showSearch
                    placeholder="选择账户"
                    optionFilterProp="label"
                    notFoundContent={accounts.length === 0 ? '还没有账户，请先去"交易账户"创建' : '未匹配'}
                    options={accounts.map(a => ({
                      value: a.id,
                      label: `${a.name} (${a.exchange}/${a.marketType}) [${a.id}]`,
                    }))}
                  />
                </Form.Item>
              </Col>
              <Col xs={24} md={16}>
                <Form.Item name="description" label="描述">
                  <Input.TextArea rows={1} placeholder="策略说明" />
                </Form.Item>
              </Col>
            </Row>

            {/* 网格几何 —— V1 简化版：区间 + 等差/等比 + 方向 + 预览 */}
            <Divider titlePlacement="start">网格几何 (V1)</Divider>
            <Row gutter={16}>
              <Col xs={24} md={4}>
                <Form.Item name="side" label="方向" rules={[{ required: true }]}
                           tooltip="LONG_ONLY 只做多；SHORT_ONLY 只做空；BOTH 双向（需 Hedge Mode）">
                  <Select options={[
                    { value: 'LONG_ONLY', label: '📈 只做多' },
                    { value: 'SHORT_ONLY', label: '📉 只做空' },
                    { value: 'BOTH', label: '↕️ 双向' },
                  ]} />
                </Form.Item>
              </Col>
              <Col xs={24} md={4}>
                <Form.Item name="gridMode" label="价格分布" rules={[{ required: true }]}
                           tooltip="等差：每格固定金额；等比：每格固定百分比">
                  <Select options={[
                    { value: 'ARITHMETIC', label: '等差' },
                    { value: 'GEOMETRIC', label: '等比' },
                  ]} />
                </Form.Item>
              </Col>
              <Col xs={24} md={4}>
                <Form.Item name="lowerPrice" label="区间下限" rules={[{ required: true }]}>
                  <InputNumber min={0} step={1} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
              <Col xs={24} md={4}>
                <Form.Item name="upperPrice" label="区间上限" rules={[{ required: true }]}>
                  <InputNumber min={0} step={1} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
              <Col xs={24} md={8}>
                <Form.Item name="gridCount" label="网格数量" rules={[{ required: true }]}
                           tooltip="区间分成多少格（价位数 = 格数 + 1）">
                  <InputNumber min={2} max={500} step={1} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
              <Col xs={24} md={8}>
                <Form.Item name="qtyPerGrid" label="单格数量" rules={[{ required: true }]}
                           tooltip="每格挂单数量。ETH 建议 0.01-0.5">
                  <InputNumber min={0.001} step={0.001} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
              <Col xs={24} md={8}>
                <Form.Item name="windowSizePerSide" label="每侧窗口大小"
                           tooltip="每侧挂多少档（增量挂单，不一次挂满）">
                  <InputNumber min={1} max={20} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
            </Row>

            {/* 预览面板 —— 独立行，参数变化 debounce 300ms 自动刷新 */}
            <Row gutter={16} style={{ marginBottom: 16 }}>
              <Col xs={24}>
                <Form.Item shouldUpdate noStyle>
                  {({ getFieldsValue }) => {
                    const v = getFieldsValue([
                      'symbol', 'side', 'gridMode',
                      'lowerPrice', 'upperPrice', 'gridCount',
                      'qtyPerGrid', 'leverage', 'windowSizePerSide',
                    ]);
                    const currentPrice = v.lowerPrice && v.upperPrice
                      ? (Number(v.lowerPrice) + Number(v.upperPrice)) / 2
                      : undefined;
                    return (
                      <GridPreviewPanel req={{
                        symbol: v.symbol,
                        side: v.side,
                        mode: v.gridMode,
                        lowerPrice: v.lowerPrice,
                        upperPrice: v.upperPrice,
                        gridCount: v.gridCount,
                        qtyPerGrid: v.qtyPerGrid,
                        currentPrice,
                        leverage: v.leverage,
                        windowSizePerSide: v.windowSizePerSide,
                      }} />
                    );
                  }}
                </Form.Item>
              </Col>
            </Row>

            <Divider titlePlacement="start">进阶（V2 自适应/风控 - V1 可留空）</Divider>
            <Row gutter={16}>
              <Col xs={24} md={8}>
                <Form.Item name="baseStepPct" label="基础步长% (V1 自动推导)"
                           tooltip="V1 会根据 lower/upper/gridCount/gridMode 自动填入此字段；V2 自适应步长把它当作基线倍率的起点">
                  <InputNumber min={0.05} max={5} step={0.05} style={{ width: '100%' }} addonAfter="%" disabled />
                </Form.Item>
              </Col>
              <Col xs={24} md={8}>
                <Form.Item name="minimumStepSafetyMultiplier" label="最小步长保护倍数"
                           tooltip="min_step = spread × 此值。防 GTX 拒绝">
                  <InputNumber min={1} max={20} step={0.5} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
              <Col xs={24} md={8}>
                <Form.Item name="hedgeMode" label="Hedge 双向" valuePropName="checked"
                           getValueFromEvent={(v) => (v ? 1 : 0)} getValueProps={(v) => ({ checked: v === 1 })}>
                  <Switch />
                </Form.Item>
              </Col>
              <Col xs={24} md={8}>
                <Form.Item name="adaptiveStepEnabled" label="自适应步长" valuePropName="checked"
                           getValueFromEvent={(v) => (v ? 1 : 0)} getValueProps={(v) => ({ checked: v === 1 })}>
                  <Switch />
                </Form.Item>
              </Col>
            </Row>

            {/* 风控上限 */}
            <Divider titlePlacement="start">风控硬上限</Divider>
            <Row gutter={16}>
              <Col xs={24} md={8}>
                <Form.Item name="maxPositionQty" label="单侧最大持仓量" rules={[{ required: true }]}
                           tooltip="单侧 (LONG/SHORT) 累积仓位上限；触及即停止开新单">
                  <InputNumber min={0.001} step={0.01} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
              <Col xs={24} md={8}>
                <Form.Item name="maxNotionalUsd" label="最大名义 USD">
                  <InputNumber min={100} step={1000} style={{ width: '100%' }} addonAfter="USDC" />
                </Form.Item>
              </Col>
              <Col xs={24} md={8}>
                <Form.Item name="maxDeltaQty" label="最大 Delta 敞口">
                  <InputNumber min={0} step={0.01} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
              <Col xs={24} md={6}>
                <Form.Item name="leverage" label="杠杆倍数">
                  <InputNumber min={1} max={20} style={{ width: '100%' }} addonAfter="x" />
                </Form.Item>
              </Col>
              <Col xs={24} md={6}>
                <Form.Item name="marginType" label="保证金模式">
                  <Select options={[{ value: 'ISOLATED', label: '逐仓' }, { value: 'CROSS', label: '全仓' }]} />
                </Form.Item>
              </Col>
            </Row>

            {/* 5 层保护 */}
            <Divider titlePlacement="start">分层保护阈值（滞回）</Divider>
            <Row gutter={16}>
              <Col xs={24} md={8}>
                <Form.Item name="trendLayer2AdxEnter" label="L2 进入 ADX">
                  <InputNumber min={10} max={100} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
              <Col xs={24} md={8}>
                <Form.Item name="trendLayer1Interval" label="L1 主 K 线周期（自适应步长）">
                  <Select options={['1m','3m','5m','15m','30m','1h'].map(v => ({ value: v, label: v }))} />
                </Form.Item>
              </Col>
              <Col xs={24} md={8}>
                <Form.Item name="trendLayer2AdxExit" label="L2 退出 ADX">
                  <InputNumber min={5} max={90} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
              <Col xs={24} md={8}>
                <Form.Item name="trendLayer2Interval" label="L2 判据周期">
                  <Select options={['5m','15m','30m','1h','4h'].map(v => ({ value: v, label: v }))} />
                </Form.Item>
              </Col>
              <Col xs={24} md={8}>
                <Form.Item name="trendLayer3AdxEnter" label="L3 进入 ADX">
                  <InputNumber min={10} max={100} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
              <Col xs={24} md={8}>
                <Form.Item name="trendLayer3AdxExit" label="L3 退出 ADX">
                  <InputNumber min={5} max={90} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
              <Col xs={24} md={8}>
                <Form.Item name="trendLayer3Interval" label="L3 判据周期">
                  <Select options={['15m','30m','1h','4h'].map(v => ({ value: v, label: v }))} />
                </Form.Item>
              </Col>
              <Col xs={24} md={8}>
                <Form.Item name="trendLayer4AdxEnter" label="L4 停机 ADX（无退出）">
                  <InputNumber min={20} max={100} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
              <Col xs={24} md={8}>
                <Form.Item name="trendLayer4Interval" label="L4 判据周期">
                  <Select options={['1h','4h','1d'].map(v => ({ value: v, label: v }))} />
                </Form.Item>
              </Col>
            </Row>

            {/* 分侧减仓 */}
            <Divider titlePlacement="start">分侧减仓 / 停机 (Layer 3-5)</Divider>
            <Row gutter={16}>
              <Col xs={12} md={6}>
                <Form.Item name="longReducePnlPct" label="LONG 减仓浮亏%">
                  <InputNumber min={0.1} max={10} step={0.1} style={{ width: '100%' }} addonAfter="%" />
                </Form.Item>
              </Col>
              <Col xs={12} md={6}>
                <Form.Item name="shortReducePnlPct" label="SHORT 减仓浮亏%">
                  <InputNumber min={0.1} max={10} step={0.1} style={{ width: '100%' }} addonAfter="%" />
                </Form.Item>
              </Col>
              <Col xs={12} md={6}>
                <Form.Item name="longStopPnlPct" label="LONG 停机浮亏%">
                  <InputNumber min={0.5} max={20} step={0.1} style={{ width: '100%' }} addonAfter="%" />
                </Form.Item>
              </Col>
              <Col xs={12} md={6}>
                <Form.Item name="shortStopPnlPct" label="SHORT 停机浮亏%">
                  <InputNumber min={0.5} max={20} step={0.1} style={{ width: '100%' }} addonAfter="%" />
                </Form.Item>
              </Col>
              <Col xs={24} md={8}>
                <Form.Item name="accountEmergencyPnlPct" label="账户级熔断浮亏%"
                           tooltip="账户总浮亏 > 此值 → 全部策略停机（需先打开右侧开关）">
                  <InputNumber min={1} max={30} step={0.5} style={{ width: '100%' }} addonAfter="%" />
                </Form.Item>
              </Col>
              <Col xs={24} md={8}>
                <Form.Item name="riskAutoStopEnabled" label="启用账户级 pnl 自动停机"
                           valuePropName="checked" getValueProps={v => ({ checked: v === 1 })}
                           normalize={(v: boolean) => v ? 1 : 0}
                           tooltip="关闭时账户浮亏%阈值不生效。币安 MARGIN_CALL 事件仍强制触发（不受此开关影响）。">
                  <Switch checkedChildren="开" unCheckedChildren="关" />
                </Form.Item>
              </Col>
            </Row>

            {/* 梯队平仓 */}
            <Divider titlePlacement="start">梯队平仓 (Layer 2B)</Divider>
            <Row gutter={16}>
              <Col xs={24} md={8}>
                <Form.Item name="ladderPctOfPosition" label="梯队占累积仓位%"
                           tooltip="梯队总量 = 累积仓位 × 此值">
                  <InputNumber min={10} max={100} style={{ width: '100%' }} addonAfter="%" />
                </Form.Item>
              </Col>
              <Col xs={24} md={8}>
                <Form.Item name="ladderStepCount" label="梯队档数">
                  <InputNumber min={1} max={10} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
              <Col xs={24} md={8}>
                <Form.Item name="ladderMinReservePct" label="梯队最低价与现价缓冲%">
                  <InputNumber min={0.01} max={2} step={0.01} style={{ width: '100%' }} addonAfter="%" />
                </Form.Item>
              </Col>
            </Row>

            {/* 紧急平仓 */}
            <Divider titlePlacement="start">紧急平仓 Escape Hatch</Divider>
            <Row gutter={16}>
              <Col xs={24} md={8}>
                <Form.Item name="emergencyTakerFallbackEnabled" label="允许 taker 强平"
                           valuePropName="checked"
                           getValueFromEvent={(v) => (v ? 1 : 0)} getValueProps={(v) => ({ checked: v === 1 })}
                           tooltip="Maker-only 追单失败 + 浮亏冲阈值时，破例用市价强平（收 fee！）">
                  <Switch />
                </Form.Item>
              </Col>
              <Col xs={24} md={8}>
                <Form.Item name="emergencyTakerLossThreshold" label="Taker 触发浮亏阈值"
                           tooltip="浮亏冲到此值（如 0.03 = 3%）+ 追单失败才用 taker">
                  <InputNumber min={0.005} max={0.2} step={0.005} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
              <Col xs={24} md={8}>
                <Form.Item name="reshuffleCooldownSec" label="Reshuffle 冷却期">
                  <InputNumber min={30} max={3600} step={30} style={{ width: '100%' }} addonAfter="s" />
                </Form.Item>
              </Col>
            </Row>
          </Form>
        </Card>
      </Spin>
    </div>
  );
};
