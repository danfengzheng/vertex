import { useEffect, useState } from 'react';
import {
  Card, Form, Input, InputNumber, Select, Switch, Button, Space, Row, Col,
  Alert, Divider, message, Spin,
} from 'antd';
import { ArrowLeftOutlined, SaveOutlined } from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import { farbApi, FarbStrategyVO } from '../../api/farb';
import { exchangeAccountApi, ExchangeAccountVO } from '../../api/trading';

/** 资金费套利 - 策略创建/编辑表单 */
export const FarbStrategyForm = () => {
  const nav = useNavigate();
  const { id } = useParams<{ id?: string }>();
  const isEdit = !!id;
  const [form] = Form.useForm<FarbStrategyVO>();
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [accounts, setAccounts] = useState<ExchangeAccountVO[]>([]);

  useEffect(() => {
    exchangeAccountApi.list?.().then((res) => {
      if (res.code === 200 && res.data) setAccounts(res.data as ExchangeAccountVO[]);
    }).catch(() => {});

    if (isEdit && id) {
      setLoading(true);
      farbApi.get(id).then((res) => {
        if (res.code === 200 && res.data) form.setFieldsValue(res.data);
      }).finally(() => setLoading(false));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);

  const handleSave = async () => {
    setSaving(true);
    try {
      const values = await form.validateFields();
      if (isEdit && id) {
        await farbApi.update(id, values);
        message.success('保存成功');
      } else {
        await farbApi.create(values);
        message.success('创建成功');
      }
      nav('/farb');
    } catch (e: any) {
      if (e?.errorFields) {
        message.error('请检查表单必填项');
      } else {
        message.error(e?.message ?? 'save failed');
      }
    } finally {
      setSaving(false);
    }
  };

  const spotAccounts = accounts.filter((a) => a.marketType === 'SPOT');
  const perpAccounts = accounts.filter((a) => a.marketType === 'USDM');

  return (
    <Spin spinning={loading}>
      <Card
        title={
          <Space>
            <Button icon={<ArrowLeftOutlined />} onClick={() => nav('/farb')}>
              返回
            </Button>
            <span>{isEdit ? '编辑策略' : '新建策略'}</span>
          </Space>
        }
        extra={
          <Button
            type="primary"
            icon={<SaveOutlined />}
            loading={saving}
            onClick={handleSave}
          >
            保存
          </Button>
        }
      >
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message="资金费套利：中低频波段模型"
          description="现货多头 + USDM 期货空头 delta 中性。跟随 funding regime 持续性入场，跨多次结算持有。年化预期 8-20%，回撤低于网格。首次一定选 PAPER 观察 3-5 天。"
        />
        <Form
          form={form}
          layout="vertical"
          initialValues={{
            executionMode: 'PAPER',
            scanAllMarket: 1,
            min24hVolumeUsd: 10000000,
            lookbackSettlements: 6,
            minAvgFundingBps: 4.0,
            minHitRatio: 0.67,
            minLatestFundingBps: 2.0,
            maxEntryBasisBps: 20.0,
            lsrMaxExtreme: 3.0,
            oiCrowdingThresholdPct: 30.0,
            volSpikeMultiplier: 2.0,
            requireTakerConfirm: 0,
            exitRegimeBreakCount: 2,
            exitBasisDeltaBps: 60.0,
            exitTakeProfitPct: 0,   // 0=禁用 TP，让 regime 自然出场（推荐资金费套利）
            exitMaxHoldHours: 168,
            basisEmergencyBps: 120.0,
            notionalPerPair: 500,
            maxOpenPairs: 8,
            maxPairsPerSymbol: 1,
            perpLeverage: 2,
            perpMarginType: 'ISOLATED',
            useRealtimeFee: 1,
            useBnbDiscount: 1,
            spotFeeRate: 0.00075,
            perpFeeRate: 0.00036,
            minExpectedYieldPct: 8.0,
            maxDrawdownPct: 15.0,
            fundingReversalStopDays: 3,
            preSettleEnabled: 1,
            preSettleImminentMin: 10,
            preSettleStrongMin: 60,
            preSettleStrongBps: -3,
            liquidationProtectionEnabled: 1,
            liquidationSafeMovePct: undefined,
            dualMakerEnabled: 0,
            dualMakerTimeoutMs: 5000,
            dualMakerPollMs: 300,
            spotMakerFeeRate: 0.0002,
            perpMakerFeeRate: 0.0002,
            predictReversalEnabled: 0,
            predictHardScore: 0.30,
            predictSoftScore: 0.60,
            predictBreakCountTightened: 2,
            predictTpTightenedPct: 0.30,
            predictPauseNewMin: 60,
          }}
        >
          <Divider>基础</Divider>
          <Row gutter={16}>
            <Col xs={24} md={8}>
              <Form.Item name="name" label="策略名称" rules={[{ required: true }]}>
                <Input placeholder="Funding Arb #1" />
              </Form.Item>
            </Col>
            <Col xs={24} md={8}>
              <Form.Item name="executionMode" label="执行模式" rules={[{ required: true }]}>
                <Select options={[
                  { value: 'PAPER', label: '📝 PAPER (模拟)' },
                  { value: 'LIVE', label: '💰 LIVE (真实)' },
                ]} />
              </Form.Item>
            </Col>
            <Col xs={24} md={8}>
              <Form.Item name="description" label="描述">
                <Input placeholder="策略说明" />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item name="spotAccountId" label="现货账户" rules={[{ required: true }]}
                         tooltip="必须是 marketType=SPOT 的账户">
                <Select
                  showSearch
                  placeholder="选择现货账户"
                  optionFilterProp="label"
                  options={spotAccounts.map((a) => ({
                    value: a.id,
                    label: `${a.name} [${a.id}]`,
                  }))}
                />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item name="perpAccountId" label="期货账户" rules={[{ required: true }]}
                         tooltip="必须是 marketType=USDM 的账户">
                <Select
                  showSearch
                  placeholder="选择期货账户"
                  optionFilterProp="label"
                  options={perpAccounts.map((a) => ({
                    value: a.id,
                    label: `${a.name} [${a.id}]`,
                  }))}
                />
              </Form.Item>
            </Col>
          </Row>

          <Divider>Regime 判定（入场 filter）</Divider>
          <Row gutter={16}>
            <Col xs={12} md={6}>
              <Form.Item name="lookbackSettlements" label="Lookback 结算数"
                         tooltip="判定 regime 用过去多少次结算">
                <InputNumber min={2} max={24} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item name="minAvgFundingBps" label="Min 平均 funding"
                         tooltip="8h 归一化 bps">
                <InputNumber min={0} max={50} step={0.5} style={{ width: '100%' }} addonAfter="bps" />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item name="minHitRatio" label="Min 命中率">
                <InputNumber min={0} max={1} step={0.05} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item name="minLatestFundingBps" label="Min 最近 funding">
                <InputNumber min={0} max={50} step={0.5} style={{ width: '100%' }} addonAfter="bps" />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item name="maxEntryBasisBps" label="Max 入场 basis">
                <InputNumber min={5} max={200} step={5} style={{ width: '100%' }} addonAfter="bps" />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item name="lsrMaxExtreme" label="LSR 极端阈值">
                <InputNumber min={1.5} max={10} step={0.5} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item name="oiCrowdingThresholdPct" label="OI 拥挤 24h %">
                <InputNumber min={5} max={100} step={5} style={{ width: '100%' }} addonAfter="%" />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item name="volSpikeMultiplier" label="Vol 骤升倍数">
                <InputNumber min={1.2} max={5} step={0.1} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item name="requireTakerConfirm" label="要求 Taker 确认"
                         valuePropName="checked"
                         getValueFromEvent={(v) => (v ? 1 : 0)}
                         getValueProps={(v) => ({ checked: v === 1 })}>
                <Switch />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item name="min24hVolumeUsd" label="Min 24h 名义">
                <InputNumber min={1_000_000} step={1_000_000} style={{ width: '100%' }} addonAfter="USD" />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item name="minExpectedYieldPct" label="Min 预期年化">
                <InputNumber min={0} max={100} step={1} style={{ width: '100%' }} addonAfter="%" />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item name="scanAllMarket" label="扫全市场"
                         valuePropName="checked"
                         getValueFromEvent={(v) => (v ? 1 : 0)}
                         getValueProps={(v) => ({ checked: v === 1 })}>
                <Switch />
              </Form.Item>
            </Col>
          </Row>

          <Divider>出场触发</Divider>
          <Row gutter={16}>
            <Col xs={12} md={6}>
              <Form.Item name="exitRegimeBreakCount" label="Regime break 次数">
                <InputNumber min={1} max={10} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item name="exitBasisDeltaBps" label="Basis delta 阈值">
                <InputNumber min={10} max={500} step={10} style={{ width: '100%' }} addonAfter="bps" />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item name="exitTakeProfitPct" label="Take profit (0=禁用)"
                         tooltip="资金费套利建议设为 0（禁用 TP），让 regime 自然出场；否则会白付进出手续费">
                <InputNumber min={0} max={10} step={0.1} style={{ width: '100%' }} addonAfter="%" />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item name="exitMaxHoldHours" label="Max hold (h)">
                <InputNumber min={4} max={720} step={4} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item name="basisEmergencyBps" label="Basis 紧急阈值">
                <InputNumber min={30} max={1000} step={10} style={{ width: '100%' }} addonAfter="bps" />
              </Form.Item>
            </Col>
          </Row>

          <Divider>仓位</Divider>
          <Row gutter={16}>
            <Col xs={12} md={6}>
              <Form.Item name="notionalPerPair" label="单对名义" rules={[{ required: true }]}>
                <InputNumber min={10} step={100} style={{ width: '100%' }} addonAfter="USD" />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item name="maxOpenPairs" label="Max open pairs">
                <InputNumber min={1} max={50} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item name="maxPairsPerSymbol" label="Max per symbol">
                <InputNumber min={1} max={5} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item name="perpLeverage" label="期货杠杆">
                <InputNumber min={1} max={10} style={{ width: '100%' }} addonAfter="x" />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item name="perpMarginType" label="保证金模式">
                <Select options={[
                  { value: 'ISOLATED', label: '逐仓' },
                  { value: 'CROSS', label: '全仓' },
                ]} />
              </Form.Item>
            </Col>
          </Row>

          <Divider>手续费</Divider>
          <Row gutter={16}>
            <Col xs={12} md={6}>
              <Form.Item name="spotFeeRate" label="现货 fee rate">
                <InputNumber min={0} max={0.01} step={0.00005} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item name="perpFeeRate" label="期货 fee rate">
                <InputNumber min={0} max={0.01} step={0.00001} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item name="useBnbDiscount" label="BNB 抵扣"
                         valuePropName="checked"
                         getValueFromEvent={(v) => (v ? 1 : 0)}
                         getValueProps={(v) => ({ checked: v === 1 })}>
                <Switch />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item name="useRealtimeFee" label="实时查费"
                         valuePropName="checked"
                         getValueFromEvent={(v) => (v ? 1 : 0)}
                         getValueProps={(v) => ({ checked: v === 1 })}>
                <Switch />
              </Form.Item>
            </Col>
          </Row>

          <Divider>风控</Divider>
          <Row gutter={16}>
            <Col xs={12} md={6}>
              <Form.Item name="maxDrawdownPct" label="Max drawdown">
                <InputNumber min={5} max={50} step={1} style={{ width: '100%' }} addonAfter="%" />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item name="fundingReversalStopDays" label="Funding 反转停机 (d)">
                <InputNumber min={1} max={14} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
          </Row>

          <Divider>预结算负 rate 检测</Divider>
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 12 }}
            message="在 Binance 推送的预估下次结算 rate 转负时提前平仓"
            description="比等真实结算快 10-60 分钟。临近窗口（≤10分钟）任何负 rate 都平；中距窗口（≤60分钟）达到强阈值才平（防假信号）。"
          />
          <Row gutter={16}>
            <Col xs={12} md={6}>
              <Form.Item name="preSettleEnabled" label="启用预结算检测"
                         valuePropName="checked"
                         getValueFromEvent={(c) => (c ? 1 : 0)}
                         getValueProps={(v) => ({ checked: v === 1 })}>
                <Switch />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item name="preSettleImminentMin" label="临近窗口 (分钟)"
                         tooltip="此范围内只要预估 rate < 0 就立即平">
                <InputNumber min={1} max={30} style={{ width: '100%' }} addonAfter="min" />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item name="preSettleStrongMin" label="中距窗口 (分钟)"
                         tooltip="此范围内达到 strongBps 阈值才平（防假信号）">
                <InputNumber min={10} max={240} style={{ width: '100%' }} addonAfter="min" />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item name="preSettleStrongBps" label="中距阈值 (bps)"
                         tooltip="中距窗口触发阈值，负数（例如 -3 = rate ≤ -3bps 才平）">
                <InputNumber max={0} step={0.5} style={{ width: '100%' }} addonAfter="bps" />
              </Form.Item>
            </Col>
          </Row>

          <Divider>强平保护</Divider>
          <Alert
            type="warning"
            showIcon
            style={{ marginBottom: 12 }}
            message="Delta 中性 ≠ 保证金安全"
            description="现货多 + 合约空 的整体 PnL 接近 0，但 perp 独立保证金账户可能因单侧亏损被强平。价格反向拉升 (1/leverage)% 就到强平线（2x → 50%，3x → 33%）。留 NULL 让系统按 0.7 × (1/leverage) 自动算安全阈值。"
          />
          <Row gutter={16}>
            <Col xs={12} md={6}>
              <Form.Item name="liquidationProtectionEnabled" label="启用强平保护"
                         valuePropName="checked"
                         getValueFromEvent={(c) => (c ? 1 : 0)}
                         getValueProps={(v) => ({ checked: v === 1 })}>
                <Switch />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item name="liquidationSafeMovePct" label="安全平仓阈值 (%)"
                         tooltip="perp 反向波动到此比例时主动平仓。留空 = 自动 0.7 × (1/leverage)">
                <InputNumber min={5} max={50} step={1} style={{ width: '100%' }} addonAfter="%" placeholder="auto" />
              </Form.Item>
            </Col>
          </Row>

          <Divider>Dual-maker Post-only 执行（LIVE 专用，可选）</Divider>
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 12 }}
            message="双腿 post-only + 先成即市价对方腿 → fee 节省 30-50%"
            description="LIVE 模式下，入场/常规出场先并发挂 spot LIMIT_MAKER + perp GTX。哪腿先 filled 立即撤对方腿并 MARKET 追单，敞口 ~100ms。超时都没成 → 全 MARKET 兜底。BASIS_EMERGENCY/LIQUIDATION_RISK 场景自动跳过，直接 MARKET。PAPER 模式不生效（走原有模拟器）。"
          />
          <Row gutter={16}>
            <Col xs={12} md={6}>
              <Form.Item name="dualMakerEnabled" label="启用 dual-maker"
                         valuePropName="checked"
                         getValueFromEvent={(c) => (c ? 1 : 0)}
                         getValueProps={(v) => ({ checked: v === 1 })}>
                <Switch />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item name="dualMakerTimeoutMs" label="Post-only 超时">
                <InputNumber min={1000} max={30000} step={500} style={{ width: '100%' }} addonAfter="ms" />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item name="dualMakerPollMs" label="Fill 轮询间隔">
                <InputNumber min={100} max={2000} step={100} style={{ width: '100%' }} addonAfter="ms" />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={16}>
            <Col xs={12} md={6}>
              <Form.Item name="spotMakerFeeRate" label="Spot maker fee"
                         tooltip="Binance VIP0 spot maker = 0.02%（0.0002）；含 BNB 折扣可再低">
                <InputNumber min={0} max={0.001} step={0.00001} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item name="perpMakerFeeRate" label="Perp maker fee"
                         tooltip="Binance VIP0 perp maker = 0.02%（0.0002）">
                <InputNumber min={0} max={0.001} step={0.00001} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
          </Row>

          <Divider>预测反转（v2 软/硬两档，可选）</Divider>
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 12 }}
            message="启用后基于 basis / OI / LSR / taker BSR / funding slope 五因子打分"
            description="healthScore < 硬阈值 → 立即平仓；< 软阈值 → 停开新仓 + 收紧 TP 和 regime break 次数。首次使用建议先在 PAPER 模式跑 3-5 天验证。"
          />
          <Row gutter={16}>
            <Col xs={12} md={6}>
              <Form.Item name="predictReversalEnabled" label="启用预测反转"
                         valuePropName="checked"
                         getValueFromEvent={(c) => (c ? 1 : 0)}
                         getValueProps={(v) => ({ checked: v === 1 })}>
                <Switch />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item name="predictHardScore" label="硬阈值（<= 立即平仓）">
                <InputNumber min={0.05} max={0.5} step={0.05} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item name="predictSoftScore" label="软阈值（<= 停开+收紧）">
                <InputNumber min={0.3} max={0.9} step={0.05} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item name="predictPauseNewMin" label="暂停开新仓 (分钟)">
                <InputNumber min={5} max={720} step={5} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item name="predictBreakCountTightened" label="收紧后 break 次数">
                <InputNumber min={1} max={10} step={1} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item name="predictTpTightenedPct" label="收紧后 TP">
                <InputNumber min={0.05} max={2} step={0.05} style={{ width: '100%' }} addonAfter="%" />
              </Form.Item>
            </Col>
          </Row>
        </Form>
      </Card>
    </Spin>
  );
};

export default FarbStrategyForm;
