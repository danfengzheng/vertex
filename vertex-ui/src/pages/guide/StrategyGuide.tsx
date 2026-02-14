import { Tabs, Table, Typography, Card, Tag } from 'antd';
import { BookOutlined, ApiOutlined, ClusterOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';

const { Title, Paragraph, Text } = Typography;

/** 指标说明数据 */
const INDICATOR_DESCRIPTIONS = [
  { code: 'MA', name: 'MA', nameZh: '简单移动平均线', signal: '收盘价上穿/下穿均线 0.1%', usage: '中长期趋势判断' },
  { code: 'EMA', name: 'EMA', nameZh: '指数移动平均线', signal: '同 MA，对近期价格更敏感', usage: '短中期趋势跟踪' },
  { code: 'RSI', name: 'RSI', nameZh: '相对强弱指数', signal: 'RSI<30 超买买入, RSI>70 超卖卖出', usage: '震荡市超买超卖' },
  { code: 'MACD', name: 'MACD', nameZh: '移动平均收敛散度', signal: '金叉/死叉（柱状图正负翻转）', usage: '中期趋势转折' },
  { code: 'BOLL', name: 'BOLL', nameZh: '布林带', signal: '收盘价跌破下轨买/突破上轨卖', usage: '价格偏离均值判断' },
  { code: 'KDJ', name: 'KDJ', nameZh: '随机指标', signal: 'K 上穿 D 金叉/K 下穿 D 死叉', usage: '短期超买超卖' },
  { code: 'ATR', name: 'ATR', nameZh: '平均真实波幅', signal: '始终 NEUTRAL，波动率参考', usage: '止损位设定（如 2×ATR）' },
  { code: 'VWAP', name: 'VWAP', nameZh: '成交量加权均价', signal: '价格偏离 VWAP 0.2%', usage: '日内均值回归' },
  { code: 'STOCH_RSI', name: 'STOCH_RSI', nameZh: '随机RSI', signal: 'K 上穿 D 且 K<20 / K 下穿 D 且 K>80', usage: '短期超买超卖反转' },
  { code: 'WR', name: 'WR', nameZh: '威廉指标', signal: '%R<-80 超卖 / %R>-20 超买', usage: '短线快速进出' },
  { code: 'SAR', name: 'SAR', nameZh: '抛物线转向', signal: '价格上穿/下穿 SAR 点反转', usage: '趋势追踪止损' },
  { code: 'ADX', name: 'ADX', nameZh: '平均趋向指数', signal: 'ADX>阈值 且 +DI>-DI 买 / -DI>+DI 卖', usage: '趋势强度过滤' },
  { code: 'SUPERTREND', name: 'SUPERTREND', nameZh: '超级趋势', signal: '趋势 UP/DOWN 翻转', usage: '自适应趋势跟踪' },
  { code: 'VOL_CONFIRM', name: 'VOL_CONFIRM', nameZh: '成交量确认', signal: '放量+涨买/放量+跌卖', usage: '过滤假突破' },
  { code: 'OBV', name: 'OBV', nameZh: '能量潮', signal: 'OBV 高于/低于信号线 1%', usage: '量价背离检测' },
];

/** 指标参数数据 */
const INDICATOR_PARAMS: Record<string, { param: string; default: string; range: string; desc: string }[]> = {
  MA: [{ param: 'period', default: '20', range: '5-200', desc: '计算周期' }],
  EMA: [{ param: 'period', default: '20', range: '5-200', desc: '计算周期' }],
  RSI: [{ param: 'period', default: '14', range: '6-25', desc: '计算周期' }],
  MACD: [
    { param: 'fast', default: '12', range: '5-20', desc: '快线周期' },
    { param: 'slow', default: '26', range: '20-40', desc: '慢线周期' },
    { param: 'signal', default: '9', range: '5-15', desc: '信号线周期' },
  ],
  BOLL: [
    { param: 'period', default: '20', range: '10-50', desc: '中轨周期' },
    { param: 'multiplier', default: '2.0', range: '1.0-3.0', desc: '标准差倍数' },
  ],
  KDJ: [
    { param: 'rsvPeriod', default: '9', range: '5-21', desc: 'RSV 周期' },
    { param: 'kPeriod', default: '3', range: '2-5', desc: 'K 平滑（预留）' },
    { param: 'dPeriod', default: '3', range: '2-5', desc: 'D 平滑（预留）' },
  ],
  ATR: [{ param: 'period', default: '14', range: '7-21', desc: '计算周期' }],
  VWAP: [],
  STOCH_RSI: [
    { param: 'rsiPeriod', default: '14', range: '6-25', desc: 'RSI 周期' },
    { param: 'stochPeriod', default: '14', range: '6-25', desc: 'Stochastic 周期' },
    { param: 'kSmooth', default: '3', range: '2-5', desc: 'K 平滑' },
    { param: 'dSmooth', default: '3', range: '2-5', desc: 'D 平滑' },
  ],
  WR: [{ param: 'period', default: '14', range: '5-21', desc: '回看周期' }],
  SAR: [
    { param: 'afStart', default: '0.02', range: '0.01-0.05', desc: '加速因子初值' },
    { param: 'afStep', default: '0.02', range: '0.01-0.04', desc: '加速因子步长' },
    { param: 'afMax', default: '0.2', range: '0.1-0.4', desc: '加速因子上限' },
  ],
  ADX: [
    { param: 'period', default: '14', range: '7-30', desc: '计算周期' },
    { param: 'trendThreshold', default: '25', range: '15-40', desc: '趋势强度阈值' },
  ],
  SUPERTREND: [
    { param: 'period', default: '10', range: '5-30', desc: 'ATR 周期' },
    { param: 'multiplier', default: '3.0', range: '1.0-6.0', desc: 'ATR 倍数' },
  ],
  VOL_CONFIRM: [
    { param: 'period', default: '20', range: '5-50', desc: '均量周期' },
    { param: 'volMultiplier', default: '1.5', range: '1.0-3.0', desc: '放量倍数阈值' },
  ],
  OBV: [{ param: 'signalPeriod', default: '10', range: '3-30', desc: '信号线周期' }],
};

/** 策略组合推荐 */
const STRATEGY_COMBINATIONS = [
  { combo: 'MACD + MA', weights: '60 : 40', scene: '单边行情趋势跟踪' },
  { combo: 'RSI + BOLL', weights: '50 : 50', scene: '横盘震荡反转' },
  { combo: 'MACD + RSI + BOLL', weights: '40 : 30 : 30', scene: '综合策略' },
  { combo: 'KDJ + EMA', weights: '55 : 45', scene: '短周期频繁交易' },
  { combo: 'MACD + RSI + ATR', weights: '50 : 30 : 20', scene: '趋势确认+波动过滤' },
  { combo: 'STOCH_RSI + WR + VWAP', weights: '40 : 35 : 25', scene: '1m-15m 高频短线' },
  { combo: 'VWAP + RSI', weights: '55 : 45', scene: '日内均值回归' },
  { combo: 'WR + KDJ', weights: '50 : 50', scene: '超短线快速进出' },
  { combo: 'SAR + ADX', weights: '55 : 45', scene: '趋势追踪，ADX 过滤震荡' },
  { combo: 'SUPERTREND + ADX', weights: '60 : 40', scene: '自适应趋势+强度确认' },
  { combo: 'SUPERTREND + SAR + ADX', weights: '40 : 35 : 25', scene: '全维度趋势跟踪' },
  { combo: 'SUPERTREND + VOL_CONFIRM', weights: '60 : 40', scene: '趋势+放量确认，过滤假突破' },
  { combo: 'MACD + OBV + VOL_CONFIRM', weights: '40 : 35 : 25', scene: '动量+资金流向+放量' },
  { combo: 'RSI + OBV', weights: '55 : 45', scene: '超买超卖+量能背离' },
];

export const StrategyGuide = () => {
  const { t } = useTranslation();

  const indicatorDescColumns = [
    { title: t('text.guide.indicatorCode'), dataIndex: 'code', key: 'code', width: 120, render: (v: string) => <Tag>{v}</Tag> },
    { title: t('text.guide.indicatorName'), dataIndex: 'nameZh', key: 'nameZh' },
    { title: t('text.guide.signalLogic'), dataIndex: 'signal', key: 'signal', ellipsis: true },
    { title: t('text.guide.usage'), dataIndex: 'usage', key: 'usage' },
  ];

  const paramsData = Object.entries(INDICATOR_PARAMS).flatMap(([indicator, rows]) =>
    rows.length > 0
      ? rows.map((r) => ({ indicator, ...r }))
      : [{ indicator, param: '-', default: '-', range: '-', desc: t('text.guide.noParams') }]
  );

  const paramsColumns = [
    { title: t('text.guide.indicatorCode'), dataIndex: 'indicator', key: 'indicator', width: 120, render: (v: string) => <Tag>{v}</Tag> },
    { title: t('text.guide.paramName'), dataIndex: 'param', key: 'param', width: 120 },
    { title: t('text.guide.defaultValue'), dataIndex: 'default', key: 'default', width: 100 },
    { title: t('text.guide.range'), dataIndex: 'range', key: 'range', width: 100 },
    { title: t('text.guide.paramDesc'), dataIndex: 'desc', key: 'desc' },
  ];

  const comboColumns = [
    { title: t('text.guide.combo'), dataIndex: 'combo', key: 'combo', render: (v: string) => <Text strong>{v}</Text> },
    { title: t('text.guide.weights'), dataIndex: 'weights', key: 'weights', width: 140 },
    { title: t('text.guide.scene'), dataIndex: 'scene', key: 'scene' },
  ];

  return (
    <div>
      <div style={{ marginBottom: 24 }}>
        <Title level={4} style={{ margin: 0 }}>
          {t('text.guide.title')}
        </Title>
        <Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0 }}>
          {t('text.guide.subtitle')}
        </Paragraph>
      </div>

      <Tabs
        defaultActiveKey="indicators"
        items={[
          {
            key: 'indicators',
            label: (
              <span>
                <BookOutlined /> {t('text.guide.tabIndicators')}
              </span>
            ),
            children: (
              <Card size="small">
                <Paragraph type="secondary" style={{ marginBottom: 16 }}>
                  {t('text.guide.tabIndicatorsDesc')}
                </Paragraph>
                <Table
                  columns={indicatorDescColumns}
                  dataSource={INDICATOR_DESCRIPTIONS}
                  rowKey="code"
                  pagination={false}
                  size="small"
                  scroll={{ x: 600 }}
                />
              </Card>
            ),
          },
          {
            key: 'params',
            label: (
              <span>
                <ApiOutlined /> {t('text.guide.tabParams')}
              </span>
            ),
            children: (
              <Card size="small">
                <Paragraph type="secondary" style={{ marginBottom: 16 }}>
                  {t('text.guide.tabParamsDesc')}
                </Paragraph>
                <Table
                  columns={paramsColumns}
                  dataSource={paramsData}
                  rowKey={(r) => `${r.indicator}-${r.param}`}
                  pagination={false}
                  size="small"
                  scroll={{ x: 700 }}
                />
              </Card>
            ),
          },
          {
            key: 'combinations',
            label: (
              <span>
                <ClusterOutlined /> {t('text.guide.tabCombos')}
              </span>
            ),
            children: (
              <Card size="small">
                <Paragraph type="secondary" style={{ marginBottom: 16 }}>
                  {t('text.guide.tabCombosDesc')}
                </Paragraph>
                <Table
                  columns={comboColumns}
                  dataSource={STRATEGY_COMBINATIONS}
                  rowKey="combo"
                  pagination={false}
                  size="small"
                />
              </Card>
            ),
          },
        ]}
      />
    </div>
  );
};
