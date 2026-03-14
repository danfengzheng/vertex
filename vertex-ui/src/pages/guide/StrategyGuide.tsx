import { Tabs, Table, Typography, Card, Tag, Collapse, Row, Col, Alert, Space, Badge } from 'antd';
import {
  BookOutlined,
  ApiOutlined,
  ClusterOutlined,
  InfoCircleOutlined,
  CheckCircleOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';

const { Title, Paragraph, Text } = Typography;

// ── 指标总览数据（含未实现）──────────────────────────────────────────────────
const INDICATOR_DESCRIPTIONS = [
  { code: 'MA',         nameZh: '简单移动平均线',   signal: '收盘价上穿/下穿均线 0.1%',                             usage: '中长期趋势判断',       implemented: true },
  { code: 'EMA',        nameZh: '指数移动平均线',   signal: '同 MA，对近期价格更敏感',                              usage: '短中期趋势跟踪',       implemented: true },
  { code: 'RSI',        nameZh: '相对强弱指数',     signal: 'RSI<30 买入；RSI>70 → NEUTRAL（不产生卖信号）',        usage: '震荡市超卖捕捉',       implemented: true },
  { code: 'MACD',       nameZh: '移动平均收敛散度', signal: '柱状图正/负翻转（金叉/死叉）',                         usage: '中期趋势转折',         implemented: true },
  { code: 'BOLL',       nameZh: '布林带',           signal: '收盘价跌破下轨买 / 突破上轨卖',                        usage: '价格偏离均值判断',     implemented: true },
  { code: 'KDJ',        nameZh: '随机指标',         signal: 'K 上穿 D 金叉买 / K 下穿 D 死叉卖（K>80 时抑制）',    usage: '短期超买超卖',         implemented: true },
  { code: 'ATR',        nameZh: '平均真实波幅',     signal: '始终 NEUTRAL，波动率参考',                            usage: '止损位设定（2×ATR）',  implemented: true },
  { code: 'VWAP',       nameZh: '成交量加权均价',   signal: '突破 VWAP 且偏离 ≤ deviationPct%，超出则 NEUTRAL',    usage: '短线突破确认，避免追高',implemented: true },
  { code: 'STOCH_RSI',  nameZh: '随机RSI',          signal: 'K>D 且 K<20 买 / K<D 且 K>80 卖（极值区触发）',       usage: '短期超买超卖反转',     implemented: true },
  { code: 'WR',         nameZh: '威廉指标',         signal: '%R<-80 超卖买 / %R>-20 超买卖',                       usage: '短线快速进出',         implemented: true },
  { code: 'SAR',        nameZh: '抛物线转向',       signal: 'SAR 从价格上方翻到下方买 / 下方翻到上方卖',            usage: '趋势追踪止损',         implemented: true },
  { code: 'ADX',        nameZh: '平均趋向指数',     signal: 'ADX>阈值 且 +DI>-DI 买 / -DI>+DI 卖',               usage: '趋势强度过滤',         implemented: true },
  { code: 'SUPERTREND', nameZh: '超级趋势',         signal: '趋势 UP→DOWN 翻转卖 / DOWN→UP 翻转买',                usage: '自适应趋势跟踪',       implemented: true },
  { code: 'VOL_CONFIRM',nameZh: '成交量确认',       signal: '放量（volRatio>倍数）+ 涨买 / 放量+跌卖',              usage: '过滤假突破',           implemented: true },
  { code: 'OBV',        nameZh: '能量潮',           signal: 'OBV > 信号线 1% 买 / OBV < 信号线 -1% 卖',            usage: '量价背离检测',         implemented: true },
  { code: 'CCI',        nameZh: '顺势指标',         signal: 'CCI>100 超买 / CCI<-100 超卖',                        usage: '动量摆动、趋势强度',   implemented: false },
  { code: 'MFI',        nameZh: '资金流量指标',     signal: 'MFI>80 超买 / MFI<20 超卖',                           usage: '结合价格的量能指标',   implemented: false },
  { code: 'CMO',        nameZh: '钱德动量摆动',     signal: 'CMO>50 多头 / CMO<-50 空头',                          usage: '动量方向判断',         implemented: false },
  { code: 'TRIX',       nameZh: '三重指数平滑',     signal: 'TRIX 上穿/下穿信号线',                                usage: '过滤噪音的趋势指标',   implemented: false },
  { code: 'KELTNER',    nameZh: '肯特纳通道',       signal: '价格突破通道上下轨',                                  usage: '基于 ATR 的波动通道',  implemented: false },
  { code: 'ICHIMOKU',   nameZh: '一目均衡表',       signal: '云图、转换线、基准线交叉',                            usage: '多维度趋势与支撑阻力', implemented: false },
  { code: 'DONCHIAN',   nameZh: '唐奇安通道',       signal: '价格突破 N 日高/低',                                  usage: '突破与跟踪止损',       implemented: false },
  { code: 'DEMARKER',   nameZh: 'DeMarker 指标',    signal: 'DeM>0.7 超买 / DeM<0.3 超卖',                        usage: '买卖压力比较',         implemented: false },
  { code: 'ROC',        nameZh: '变动率指标',       signal: 'ROC 上穿/下穿零轴',                                  usage: '价格变动百分比动量',   implemented: false },
];

// ── 指标参数数据 ─────────────────────────────────────────────────────────────
const INDICATOR_PARAMS: Record<string, { param: string; default: string; range: string; desc: string }[]> = {
  MA:         [{ param: 'period',         default: '20',   range: '5-200',   desc: '计算周期' }],
  EMA:        [{ param: 'period',         default: '20',   range: '5-200',   desc: '计算周期' }],
  RSI:        [{ param: 'period',         default: '14',   range: '6-25',    desc: '计算周期' }],
  MACD:       [
    { param: 'fast',       default: '12',   range: '5-20',    desc: '快线周期' },
    { param: 'slow',       default: '26',   range: '20-40',   desc: '慢线周期' },
    { param: 'signal',     default: '9',    range: '5-15',    desc: '信号线周期' },
  ],
  BOLL:       [
    { param: 'period',     default: '20',   range: '10-50',   desc: '中轨周期' },
    { param: 'multiplier', default: '2.0',  range: '1.0-3.0', desc: '标准差倍数' },
  ],
  KDJ:        [
    { param: 'rsvPeriod',  default: '9',    range: '5-21',    desc: 'RSV 周期' },
    { param: 'kPeriod',    default: '3',    range: '2-5',     desc: 'K 平滑（预留）' },
    { param: 'dPeriod',    default: '3',    range: '2-5',     desc: 'D 平滑（预留）' },
  ],
  ATR:        [{ param: 'period',         default: '14',   range: '7-21',    desc: '计算周期' }],
  VWAP:       [{ param: 'deviationPct',   default: '0.2',  range: '0.05-5.0',desc: '有效突破偏离阈值（%），超出则视为强弩之末' }],
  STOCH_RSI:  [
    { param: 'rsiPeriod',  default: '14',   range: '6-25',    desc: 'RSI 周期' },
    { param: 'stochPeriod',default: '14',   range: '6-25',    desc: 'Stochastic 周期' },
    { param: 'kSmooth',    default: '3',    range: '2-5',     desc: 'K 平滑' },
    { param: 'dSmooth',    default: '3',    range: '2-5',     desc: 'D 平滑' },
  ],
  WR:         [{ param: 'period',         default: '14',   range: '5-21',    desc: '回看周期' }],
  SAR:        [
    { param: 'afStart',    default: '0.02', range: '0.01-0.05',desc: '加速因子初值' },
    { param: 'afStep',     default: '0.02', range: '0.01-0.04',desc: '加速因子步长' },
    { param: 'afMax',      default: '0.2',  range: '0.1-0.4', desc: '加速因子上限' },
  ],
  ADX:        [
    { param: 'period',        default: '14',  range: '7-30',   desc: '计算周期' },
    { param: 'trendThreshold',default: '25',  range: '15-40',  desc: '趋势强度阈值' },
  ],
  SUPERTREND: [
    { param: 'period',     default: '10',   range: '5-30',    desc: 'ATR 周期' },
    { param: 'multiplier', default: '3.0',  range: '1.0-6.0', desc: 'ATR 倍数' },
  ],
  VOL_CONFIRM:[
    { param: 'period',        default: '20',  range: '5-50',   desc: '均量周期' },
    { param: 'volMultiplier', default: '1.5', range: '1.0-3.0',desc: '放量倍数阈值' },
  ],
  OBV:        [{ param: 'signalPeriod',   default: '10',   range: '3-30',    desc: '信号线周期' }],
  CCI:        [{ param: 'period',         default: '20',   range: '10-50',   desc: '计算周期' }],
  MFI:        [{ param: 'period',         default: '14',   range: '5-25',    desc: '计算周期' }],
  CMO:        [{ param: 'period',         default: '14',   range: '5-25',    desc: '计算周期' }],
  TRIX:       [
    { param: 'period',        default: '15',  range: '5-30',   desc: 'EMA 周期' },
    { param: 'signalPeriod',  default: '9',   range: '3-15',   desc: '信号线周期' },
  ],
  KELTNER:    [
    { param: 'period',     default: '20',   range: '10-50',   desc: 'EMA 周期' },
    { param: 'multiplier', default: '2.0',  range: '1.0-4.0', desc: 'ATR 倍数' },
  ],
  ICHIMOKU:   [
    { param: 'tenkanPeriod', default: '9',  range: '5-15',   desc: '转换线周期' },
    { param: 'kijunPeriod',  default: '26', range: '20-40',  desc: '基准线周期' },
    { param: 'senkouPeriod', default: '52', range: '40-70',  desc: '先行带周期' },
  ],
  DONCHIAN:   [{ param: 'period',         default: '20',   range: '5-55',    desc: '通道周期' }],
  DEMARKER:   [{ param: 'period',         default: '14',   range: '5-25',    desc: '计算周期' }],
  ROC:        [
    { param: 'period',        default: '12',  range: '5-25',   desc: '计算周期' },
    { param: 'signalPeriod',  default: '9',   range: '3-15',   desc: '信号线周期（可选）' },
  ],
};

// ── 策略组合推荐 ─────────────────────────────────────────────────────────────
const STRATEGY_COMBINATIONS = [
  { combo: 'MACD + MA',                    weights: '60 : 40',          scene: '单边行情趋势跟踪' },
  { combo: 'RSI + BOLL',                   weights: '50 : 50',          scene: '横盘震荡反转' },
  { combo: 'MACD + RSI + BOLL',            weights: '40 : 30 : 30',     scene: '综合策略' },
  { combo: 'KDJ + EMA',                    weights: '55 : 45',          scene: '短周期频繁交易' },
  { combo: 'MACD + RSI + ATR',             weights: '50 : 30 : 20',     scene: '趋势确认+波动过滤' },
  { combo: 'STOCH_RSI + WR + VWAP',        weights: '40 : 35 : 25',     scene: '1m-15m 短线，VWAP 确认突破方向' },
  { combo: 'VOL_CONFIRM + MACD + VWAP',    weights: '40 : 30 : 30',     scene: '5m/15m 放量突破，VWAP 过滤强弩之末' },
  { combo: 'WR + KDJ',                     weights: '50 : 50',          scene: '超短线快速进出' },
  { combo: 'SAR + ADX',                    weights: '55 : 45',          scene: '趋势追踪，ADX 过滤震荡' },
  { combo: 'SUPERTREND + ADX',             weights: '60 : 40',          scene: '自适应趋势+强度确认' },
  { combo: 'SUPERTREND + SAR + ADX',       weights: '40 : 35 : 25',     scene: '全维度趋势跟踪' },
  { combo: 'SUPERTREND + VOL_CONFIRM',     weights: '60 : 40',          scene: '趋势+放量确认，过滤假突破' },
  { combo: 'MACD + OBV + VOL_CONFIRM',     weights: '40 : 35 : 25',     scene: '动量+资金流向+放量' },
  { combo: 'RSI + OBV',                    weights: '55 : 45',          scene: '超买超卖+量能背离' },
];

// ── 指标详解数据（15 个已实现指标）────────────────────────────────────────────
type FilterMode = 'direction' | 'value' | 'direction-or-value' | 'value-only';

interface FilterExample {
  desc: string;
  condition: string;   // e.g. "rsi14 < 30"
  applyTo: '双向' | '仅买入' | '仅卖出';
}

interface IndicatorDetail {
  code: string;
  nameZh: string;
  category: '趋势' | '震荡' | '成交量' | '波动率';
  categoryColor: string;
  signalDir: '双向' | '仅BUY' | '仅NEUTRAL';
  buyCondition: string;
  sellCondition: string;
  neutralCondition: string;
  notes: string[];
  filterMode: FilterMode;
  filterModeDesc: string;
  filterExamples: FilterExample[];
  votingTip: string;
  hardFilterTip: string;
}

const INDICATOR_DETAILS: IndicatorDetail[] = [
  {
    code: 'MA', nameZh: '简单移动平均线',
    category: '趋势', categoryColor: 'blue', signalDir: '双向',
    buyCondition:  '收盘价 > MA × 1.001（上穿 0.1% 缓冲区）',
    sellCondition: '收盘价 < MA × 0.999（下穿 0.1% 缓冲区）',
    neutralCondition: '收盘价在 MA ± 0.1% 范围内（横盘贴线）',
    notes: [
      '输出字段：ma{period}（随周期变化，如 period=50 → 字段名为 ma50）',
      '绝对价格值（ma{period}）不适合配置固定阈值条件，建议使用方向校验模式',
    ],
    filterMode: 'direction', filterModeDesc: '推荐方向校验',
    filterExamples: [
      { desc: '双向方向校验（推荐）：做多时价格须在均线上方，做空时须在均线下方',    condition: '方向校验（无条件）', applyTo: '双向' },
      { desc: '仅多头过滤：只允许 BUY 信号，均线下方不开多（适合长多策略）',          condition: '方向校验（无条件）', applyTo: '仅买入' },
      { desc: '⚠️ 数值条件须手动设市价阈值；字段命名：period=50 时 field 填 ma50',     condition: 'ma50 > 市价阈值（示意）', applyTo: '仅买入' },
    ],
    votingTip: '配合不同周期的 MA（20/50/200）做多均线排列确认，如 MA20 > MA50 > MA200 三线多头',
    hardFilterTip: '使用方向校验模式：确保收盘价高于均线才允许买入，低于均线才允许卖出。可与 EMA 搭配同时过滤。字段命名：period=50 → ma50',
  },
  {
    code: 'EMA', nameZh: '指数移动平均线',
    category: '趋势', categoryColor: 'blue', signalDir: '双向',
    buyCondition:  '收盘价 > EMA × 1.001',
    sellCondition: '收盘价 < EMA × 0.999',
    neutralCondition: '收盘价在 EMA ± 0.1% 范围内',
    notes: [
      '输出字段：ema{period}（随周期变化，如 period=21 → 字段名为 ema21）',
      '对近期价格权重更大，比 MA 更灵敏；反应快但也更容易产生假信号',
    ],
    filterMode: 'direction', filterModeDesc: '推荐方向校验',
    filterExamples: [
      { desc: '双向方向校验（推荐）：做多时价格须在 EMA 上方，做空时须在 EMA 下方',    condition: '方向校验（无条件）', applyTo: '双向' },
      { desc: '仅多头过滤：只允许 BUY 信号（价格跌破 EMA 时不开多）',                   condition: '方向校验（无条件）', applyTo: '仅买入' },
      { desc: '⚠️ 数值条件须手动设市价阈值；字段命名：period=21 时 field 填 ema21',     condition: 'ema21 > 市价阈值（示意）', applyTo: '仅买入' },
    ],
    votingTip: '短周期 EMA（9/21）适合短线；长周期 EMA（50/200）适合中长线',
    hardFilterTip: '使用方向校验模式（同 MA）。EMA 反应更快，可作先行过滤器与 MA 配合使用。字段命名：period=21 → ema21，period=50 → ema50',
  },
  {
    code: 'RSI', nameZh: '相对强弱指数',
    category: '震荡', categoryColor: 'orange', signalDir: '仅BUY',
    buyCondition:  'RSI < 30（超卖区，买入信号）',
    sellCondition: '⚠️ 从不返回 SELL！RSI > 70 时返回 NEUTRAL（超买观望，不反向做空）',
    neutralCondition: '30 ≤ RSI ≤ 70，或 RSI > 70（超买）',
    notes: [
      '输出字段：rsi{period}（随周期变化，如 period=14 → rsi14，period=36 → rsi36）⚠️ 数值条件的 field 须与配置的 period 一致',
      '⚠️ RSI 是单向指标，只在超卖（<30）时给出 BUY，超买时返回 NEUTRAL 而非 SELL',
      '若将 RSI 加入投票，它只能贡献 BUY 加分，不会产生 SELL 加分',
      '使用方向校验硬过滤时，RSI 超买（>70）会返回 NEUTRAL → 导致硬过滤否决所有信号',
      '正确用法：使用数值条件，分别为 BUY/SELL 配置方向专属阈值',
    ],
    filterMode: 'value', filterModeDesc: '必须使用数值条件（方向专属）',
    filterExamples: [
      { desc: '买入时要求超卖（period=14 时字段为 rsi14）',  condition: 'rsi14 < 30',  applyTo: '仅买入' },
      { desc: '卖出时要求超买（period=36 时字段改为 rsi36）', condition: 'rsi14 > 70',  applyTo: '仅卖出' },
    ],
    votingTip: '在震荡行情中作为投票指标，超卖区贡献 BUY 加分；趋势行情中不建议作为主力指标',
    hardFilterTip: '⚠️ 不可用方向校验模式（RSI 超买返回 NEUTRAL，会否决所有信号）。必须使用数值条件并设置 applyTo，分别配置超卖（<30，仅买入）和超买（>70，仅卖出）。⚠️ 注意字段名须与周期一致：period=36 → field 填 rsi36',
  },
  {
    code: 'MACD', nameZh: '移动平均收敛散度',
    category: '趋势', categoryColor: 'blue', signalDir: '双向',
    buyCondition:  'histogramPrev < 0 且 histogram > 0（柱状图由负翻正，金叉）',
    sellCondition: 'histogramPrev > 0 且 histogram < 0（柱状图由正翻负，死叉）',
    neutralCondition: '无穿越（histogramPrev 与 histogram 同号）',
    notes: [
      '输出字段：macd（MACD 线）、signal（信号线）、histogram（柱状图当前值）、histogramPrev（柱状图前一值）',
      'macd / signal / histogram：当前最新值，可用于"柱状图为正/负"等数值过滤',
      'histogramPrev：前一根 K 线的柱状图值，与 histogram 配合实现金叉/死叉检测',
      '✅ 推荐金叉条件：buyConditions 配置 histogramPrev < 0 且 histogram > 0',
      '✅ 推荐死叉条件：sellConditions 配置 histogramPrev > 0 且 histogram < 0',
    ],
    filterMode: 'direction-or-value', filterModeDesc: '推荐数值条件（使用 histogramPrev + histogram）',
    filterExamples: [
      { desc: '金叉买入：柱状图由负翻正（histogramPrev 字段）', condition: 'histogramPrev < 0',  applyTo: '仅买入' },
      { desc: '死叉卖出：柱状图由正翻负（histogramPrev 字段）', condition: 'histogramPrev > 0',  applyTo: '仅卖出' },
      { desc: '买入时确认柱状图已为正',                         condition: 'histogram > 0',      applyTo: '仅买入' },
      { desc: '卖出时确认柱状图已为负',                         condition: 'histogram < 0',      applyTo: '仅卖出' },
    ],
    votingTip: 'MACD 是趋势确认主力，建议作为高权重（40-60）投票指标；结合 MA/EMA 效果最佳',
    hardFilterTip: '使用 histogramPrev + histogram 实现精确金叉/死叉检测：买入过滤器配置 histogramPrev < 0（applyTo=BUY），同时在 buyConditions 中加 histogram > 0 确认已完成穿越',
  },
  {
    code: 'BOLL', nameZh: '布林带',
    category: '震荡', categoryColor: 'orange', signalDir: '双向',
    buyCondition:  '收盘价 < 下轨（lower band）— 超卖、均值回归做多',
    sellCondition: '收盘价 > 上轨（upper band）— 超买、均值回归做空',
    neutralCondition: '收盘价在上下轨之间',
    notes: ['布林带宽度（stdDev）可用于判断波动性；带宽收窄后往往有方向性突破'],
    filterMode: 'direction-or-value', filterModeDesc: '推荐方向校验，也可使用 upper/lower/stdDev',
    filterExamples: [
      { desc: '买入时价格须跌破下轨',  condition: '方向校验（无条件）', applyTo: '双向' },
      { desc: '要求有足够波动性',       condition: 'stdDev > 200',      applyTo: '双向' },
    ],
    votingTip: '均值回归策略中与 RSI/KDJ 搭配；趋势策略中可作为突破确认指标',
    hardFilterTip: '方向校验：确保价格在极端位置（跌破/突破布林带）才开仓，避免在中轨附近频繁交易',
  },
  {
    code: 'KDJ', nameZh: '随机指标',
    category: '震荡', categoryColor: 'orange', signalDir: '双向',
    buyCondition:  'kPrev < dPrev 且 k > d（K 线从下方上穿 D 线，金叉）',
    sellCondition: 'kPrev > dPrev 且 k < d（K 线从上方下穿 D 线，死叉）',
    neutralCondition: '无穿越（k 与 d 相对位置未发生变化）',
    notes: [
      '输出字段：k（K 值）、d（D 值）、j（J 值）、kPrev（前一 K 值）、dPrev（前一 D 值）',
      'k / d / j：当前最新值，用于检测超买超卖程度（如 k < 20 超卖，k > 80 超买）',
      'kPrev / dPrev：前一根 K 线的 K/D 值，与当前 k/d 配合实现金叉/死叉检测',
      '✅ 推荐金叉条件：buyConditions 配置 kPrev < dPrev 且 k > d（且可加 k < 30 超卖过滤）',
      '✅ 推荐死叉条件：sellConditions 配置 kPrev > dPrev 且 k < d（且可加 k > 70 超买过滤）',
    ],
    filterMode: 'direction-or-value', filterModeDesc: '推荐数值条件（使用 kPrev + k 实现穿越检测）',
    filterExamples: [
      { desc: '金叉买入：K 线上穿 D 线（需两个条件同时配置）',   condition: 'kPrev < dPrev',  applyTo: '仅买入' },
      { desc: '金叉买入确认（与上条同用）',                       condition: 'k > d',           applyTo: '仅买入' },
      { desc: '超卖区金叉（更严格信号）',                         condition: 'k < 30',          applyTo: '仅买入' },
      { desc: '死叉卖出：K 线下穿 D 线（需两个条件同时配置）',   condition: 'kPrev > dPrev',  applyTo: '仅卖出' },
    ],
    votingTip: '短周期（1m-15m）震荡行情中效果好；与 EMA/VWAP 搭配过滤方向',
    hardFilterTip: '✅ 金叉检测：在 buyConditions 中同时配置 kPrev < dPrev（仅买入）和 k > d（仅买入），两条件同时满足才触发。可额外加 k < 30 限制在超卖区。⚠️ 注意：buyConditions 中多条件是 AND 关系',
  },
  {
    code: 'ATR', nameZh: '平均真实波幅',
    category: '波动率', categoryColor: 'purple', signalDir: '仅NEUTRAL',
    buyCondition:  '⚠️ 从不返回 BUY',
    sellCondition: '⚠️ 从不返回 SELL',
    neutralCondition: '始终返回 NEUTRAL（ATR 是波动率指标，无方向性）',
    notes: [
      '⚠️ ATR 不产生方向信号，不可用于投票或方向校验硬过滤',
      '只能作为数值条件硬过滤器，用于要求最低波动性（atr）或相对波动性（atrPercent）',
      'atrPercent = ATR / 收盘价 × 100，反映相对波动率，可跨品种比较',
      '主要用途：止损位设定（ATR × 倍数）和趋势强度过滤',
    ],
    filterMode: 'value-only', filterModeDesc: '只能使用数值条件',
    filterExamples: [
      { desc: '要求最低相对波动性（过滤横盘）', condition: 'atrPercent > 0.3', applyTo: '双向' },
      { desc: '避免波动率过高时交易',           condition: 'atrPercent < 3.0',  applyTo: '双向' },
    ],
    votingTip: '❌ 不适合作为投票指标（始终贡献 NEUTRAL 权重）',
    hardFilterTip: '✅ 适合作为波动性前置门槛：atrPercent > 0.3 确保有足够波动；atrPercent < 3.0 避免极端波动期交易。设置为双向（applyTo 为空）使其在复合投票前生效',
  },
  {
    code: 'VWAP', nameZh: '成交量加权均价',
    category: '趋势', categoryColor: 'blue', signalDir: '双向',
    buyCondition:  'deviation > 0 且 deviation ≤ deviationPct（价格在 VWAP 上方且偏离在有效范围内）',
    sellCondition: 'deviation < 0 且 deviation ≥ -deviationPct（价格在 VWAP 下方且偏离在有效范围内）',
    neutralCondition: '偏离为 0（贴线），或偏离超过阈值（过度追高/追空，风险大）',
    notes: [
      '输出字段：vwap（成交量加权均价，绝对价格值）、deviation（偏离度，单位 %）',
      'vwap：绝对价格值，随市场变动，⚠️ 不适合配置固定数值阈值条件',
      'deviation：deviation = (收盘价 - VWAP) / VWAP × 100，正值表示价格在 VWAP 上方',
      '⚠️ 参数 deviationPct 仅作为 UI 配置参考值（原用于自动判断），现需用户自行配置 deviation 数值条件',
      '✅ 推荐做多条件：buyConditions 配置 deviation > 0（在 VWAP 上方）+ deviation < 0.5（不追高）',
      '适合日内交易；跨日 VWAP 累积了历史数据，应结合日线重置策略',
    ],
    filterMode: 'direction-or-value', filterModeDesc: '推荐 deviation 数值条件',
    filterExamples: [
      { desc: '价格须在 VWAP 之上才做多',        condition: 'deviation > 0',   applyTo: '仅买入' },
      { desc: '价格须在 VWAP 之下才做空',        condition: 'deviation < 0',   applyTo: '仅卖出' },
      { desc: '不追高：偏离不超过 0.5%',         condition: 'deviation < 0.5', applyTo: '仅买入' },
      { desc: '不追空：负偏离不超过 -0.5%',      condition: 'deviation > -0.5',applyTo: '仅卖出' },
    ],
    votingTip: '日内突破策略中与 VOL_CONFIRM、MACD 搭配，确认放量且不过度偏离',
    hardFilterTip: '✅ 用 deviation 数值条件替代原 deviationPct 参数：买入时配置 deviation > 0（仅买入）+ deviation < 0.5（仅买入），同时确认在 VWAP 上方且未过度追高',
  },
  {
    code: 'STOCH_RSI', nameZh: '随机RSI',
    category: '震荡', categoryColor: 'orange', signalDir: '双向',
    buyCondition:  'stochRsiKPrev < stochRsiDPrev 且 stochRsiK > stochRsiD 且 stochRsiK < 20（超卖区金叉）',
    sellCondition: 'stochRsiKPrev > stochRsiDPrev 且 stochRsiK < stochRsiD 且 stochRsiK > 80（超买区死叉）',
    neutralCondition: '无穿越，或穿越发生在中性区间（20-80），不在极值区',
    notes: [
      '输出字段：stochRsiK（K 线值）、stochRsiD（D 线值）、stochRsiKPrev（前一 K 值）、stochRsiDPrev（前一 D 值）',
      'stochRsiK / stochRsiD：当前最新值，范围 0-100，用于判断超买（>80）超卖（<20）',
      'stochRsiKPrev / stochRsiDPrev：前一根 K 线值，与当前值配合实现 K/D 交叉检测',
      '✅ 推荐买入：buyConditions 配置 stochRsiKPrev < stochRsiDPrev、stochRsiK > stochRsiD、stochRsiK < 20',
      '✅ 推荐卖出：sellConditions 配置 stochRsiKPrev > stochRsiDPrev、stochRsiK < stochRsiD、stochRsiK > 80',
      '比 RSI 更灵敏，假信号较多，务必配合趋势指标（MACD/SuperTrend）过滤',
    ],
    filterMode: 'direction-or-value', filterModeDesc: '推荐数值条件（使用 Prev 字段实现精确交叉检测）',
    filterExamples: [
      { desc: '超卖区 K 上穿 D（金叉）第1步：前一 K < 前一 D',   condition: 'stochRsiKPrev < stochRsiDPrev', applyTo: '仅买入' },
      { desc: '超卖区 K 上穿 D（金叉）第2步：当前 K > 当前 D',   condition: 'stochRsiK > stochRsiD',         applyTo: '仅买入' },
      { desc: '超卖区门槛（更严格信号）',                         condition: 'stochRsiK < 20',               applyTo: '仅买入' },
      { desc: '超买区 K 下穿 D（死叉）',                          condition: 'stochRsiKPrev > stochRsiDPrev', applyTo: '仅卖出' },
    ],
    votingTip: '短周期高频信号指标，与 WR、BOLL 搭配进行短线反转交易',
    hardFilterTip: '✅ 精确金叉检测：在 buyConditions 中配置三个条件（stochRsiKPrev < stochRsiDPrev + stochRsiK > stochRsiD + stochRsiK < 20），所有条件同时满足才触发超卖区金叉信号',
  },
  {
    code: 'WR', nameZh: '威廉指标 (%R)',
    category: '震荡', categoryColor: 'orange', signalDir: '双向',
    buyCondition:  '%R < -80（深度超卖区）',
    sellCondition: '%R > -20（深度超买区）',
    neutralCondition: '-80 ≤ %R ≤ -20（中性区）',
    notes: [
      '输出字段：wr{period}（随周期变化，如 period=14 → wr14，period=21 → wr21）⚠️ 数值条件的 field 须与配置的 period 一致',
      '%R 范围是 -100 到 0；-80 是超卖（接近 -100），-20 是超买（接近 0）',
    ],
    filterMode: 'direction-or-value', filterModeDesc: '方向校验或数值条件均可',
    filterExamples: [
      { desc: '买入时要求深度超卖（period=14 → wr14）',  condition: 'wr14 < -85',  applyTo: '仅买入' },
      { desc: '卖出时要求深度超买（period=21 → wr21）',  condition: 'wr14 > -15',  applyTo: '仅卖出' },
    ],
    votingTip: '与 KDJ/STOCH_RSI 搭配形成超买超卖共振确认',
    hardFilterTip: '方向校验效果好：只在极值区触发。数值条件可调整触发灵敏度（默认 <-80/>-20，可收紧到 <-85/>-15）。⚠️ 注意字段名须与周期一致：period=21 → field 填 wr21',
  },
  {
    code: 'SAR', nameZh: '抛物线转向 (Parabolic SAR)',
    category: '趋势', categoryColor: 'blue', signalDir: '双向',
    buyCondition:  'SAR 从价格上方翻转到下方（下降趋势反转为上升）',
    sellCondition: 'SAR 从价格下方翻转到上方（上升趋势反转为下降）',
    neutralCondition: '无翻转（SAR 持续在同侧）',
    notes: [
      'trend 字段：1.0 = 上升趋势，-1.0 = 下降趋势；可用于方向专属数值条件',
      '在震荡市中 SAR 频繁翻转，建议配合 ADX 过滤趋势强度',
    ],
    filterMode: 'direction-or-value', filterModeDesc: '方向校验或 trend 字段',
    filterExamples: [
      { desc: '确认处于上升趋势才做多', condition: 'trend > 0',          applyTo: '仅买入' },
      { desc: '确认处于下降趋势才做空', condition: 'trend < 0',          applyTo: '仅卖出' },
      { desc: '方向校验（发生翻转）',   condition: '方向校验（无条件）', applyTo: '双向' },
    ],
    votingTip: '趋势跟踪策略中作为趋势方向确认，配合 ADX 过滤震荡期',
    hardFilterTip: '使用 trend 值条件最直观：trend > 0（上升趋势）仅买入，trend < 0（下降趋势）仅卖出',
  },
  {
    code: 'ADX', nameZh: '平均趋向指数',
    category: '趋势', categoryColor: 'blue', signalDir: '双向',
    buyCondition:  'adx >= 25 且 plusDi > minusDi（趋势足够强且多头占优）',
    sellCondition: 'adx >= 25 且 minusDi > plusDi（趋势足够强且空头占优）',
    neutralCondition: 'adx < 25（市场无明确趋势，横盘震荡）',
    notes: [
      '输出字段：adx（趋势强度，0-100）、plusDi（+DI，多头方向力量）、minusDi（-DI，空头方向力量）',
      'adx：只衡量趋势强度，不区分方向。>25 有趋势，>40 趋势强，<20 横盘',
      'plusDi / minusDi：方向指标，plusDi > minusDi 表示多头占优，反之空头占优',
      '⚠️ 参数 trendThreshold（默认 25）仅 UI 参考值（原用于自动判断），现需自行配置 adx 数值条件',
      '✅ 推荐前置门槛：filterConditions 配置 adx >= 25（双向），震荡期直接跳过',
      '✅ 推荐方向过滤：filterConditions 配置 plusDi > minusDi（仅买入），minusDi > plusDi（仅卖出）',
    ],
    filterMode: 'direction-or-value', filterModeDesc: '推荐数值条件（双向通用前置门槛）',
    filterExamples: [
      { desc: '趋势强度前置门槛（双向，推荐）',  condition: 'adx >= 25',          applyTo: '双向' },
      { desc: '多头方向力量更强',                condition: 'plusDi > minusDi',   applyTo: '仅买入' },
      { desc: '空头方向力量更强',                condition: 'minusDi > plusDi',   applyTo: '仅卖出' },
      { desc: '强趋势确认（更严格）',            condition: 'adx >= 35',          applyTo: '双向' },
    ],
    votingTip: 'ADX 作为趋势强度投票：趋势弱时贡献 NEUTRAL，趋势强且方向明确时贡献 BUY/SELL',
    hardFilterTip: '✅ 最适合作为双向通用前置门槛（applyTo 不选）：adx >= 25 确保有趋势才开仓，震荡期直接跳过。trendThreshold 参数现为参考值，实际阈值由 filterConditions 中的 adx 条件决定',
  },
  {
    code: 'SUPERTREND', nameZh: '超级趋势',
    category: '趋势', categoryColor: 'blue', signalDir: '双向',
    buyCondition:  'prevTrend < 0 且 trend > 0（趋势从下降翻转为上升）',
    sellCondition: 'prevTrend > 0 且 trend < 0（趋势从上升翻转为下降）',
    neutralCondition: 'prevTrend 与 trend 同号（无翻转，持续处于同一方向）',
    notes: [
      '输出 5 个字段：',
      'trend：当前方向标志，1.0 = 上升趋势，-1.0 = 下降趋势（最常用）',
      'prevTrend：前一根 K 线方向，与 trend 配合检测趋势翻转（1.0 或 -1.0）',
      'superTrend：趋势线当前价位（上升时 = lowerBand，下降时 = upperBand）',
      'upperBand：上轨（ATR × multiplier 动态阻力位）',
      'lowerBand：下轨（ATR × multiplier 动态支撑位）',
      '✅ 翻转信号：buyConditions 配置 prevTrend < 0 且 trend > 0（由空翻多）',
      '✅ 方向持仓过滤：filterConditions 配置 trend > 0（仅买入）确保在上升趋势中',
      '⚠️ superTrend / upperBand / lowerBand 是绝对价格值，不适合填写固定阈值，推荐用 trend / prevTrend',
    ],
    filterMode: 'direction-or-value', filterModeDesc: '方向校验、trend 字段、prevTrend 翻转，均效果出色',
    filterExamples: [
      { desc: '趋势翻转买入第1步：前一趋势为下降',  condition: 'prevTrend < 0',    applyTo: '仅买入' },
      { desc: '趋势翻转买入第2步：当前趋势为上升',  condition: 'trend > 0',         applyTo: '仅买入' },
      { desc: '持仓过滤：上升趋势中才允许买入',     condition: 'trend > 0',         applyTo: '仅买入' },
      { desc: '持仓过滤：下降趋势中才允许卖出',     condition: 'trend < 0',         applyTo: '仅卖出' },
    ],
    votingTip: '高权重趋势跟踪指标（60%），配合 VOL_CONFIRM 或 ADX 过滤假突破',
    hardFilterTip: '✅ 两种用法：①翻转信号：buyConditions 配置 prevTrend < 0 + trend > 0（捕捉趋势转折点）；②方向过滤：filterConditions 配置 trend > 0（仅买入）确保在上升趋势中开多。⚠️ 不要用 superTrend/upperBand/lowerBand 作固定阈值',
  },
  {
    code: 'VOL_CONFIRM', nameZh: '成交量确认',
    category: '成交量', categoryColor: 'green', signalDir: '双向',
    buyCondition:  'volRatio > 1.5（放量）且价格上涨（需结合价格方向指标）',
    sellCondition: 'volRatio > 1.5（放量）且价格下跌（需结合价格方向指标）',
    neutralCondition: 'volRatio ≤ 1.5（缩量，信号不可靠）',
    notes: [
      '输出字段：volRatio（成交量比率）、currentVolume（当前成交量）、avgVolume（近 N 日平均成交量）',
      'volRatio = currentVolume / avgVolume，大于 1 表示放量，小于 1 表示缩量',
      'currentVolume / avgVolume：绝对成交量值，一般不直接配置固定阈值（跨品种差异大）',
      '⚠️ 参数 volMultiplier（默认 1.5）仅 UI 配置参考值（原用于自动判断），现需自行配置 volRatio 数值条件',
      '✅ 推荐配置：filterConditions 设 volRatio > 1.5（双向前置门槛），缩量时不进行信号评估',
      'VOL_CONFIRM 本身不判断价格方向，须配合趋势指标（MACD/SuperTrend）使用',
    ],
    filterMode: 'value-only', filterModeDesc: '必须使用 volRatio 数值条件',
    filterExamples: [
      { desc: '放量前置门槛（双向通用，推荐）',  condition: 'volRatio > 1.5',  applyTo: '双向' },
      { desc: '强放量要求（突破时更严格）',       condition: 'volRatio > 2.0',  applyTo: '双向' },
      { desc: '避免成交量异常过大（防刷单）',    condition: 'volRatio < 10.0', applyTo: '双向' },
    ],
    votingTip: '配合趋势指标（MACD、SuperTrend）使用，成交量放大时为信号加分',
    hardFilterTip: '✅ 推荐作为双向通用前置门槛（applyTo 不选）：volRatio > 1.5，缩量时直接跳过复合投票节省算力，有效过滤假突破。volMultiplier 参数现为参考值，实际阈值由 filterConditions 中的 volRatio 条件决定',
  },
  {
    code: 'OBV', nameZh: '能量潮 (On-Balance Volume)',
    category: '成交量', categoryColor: 'green', signalDir: '双向',
    buyCondition:  'OBV > 信号线 × 1.01（OBV 高于信号线 1% 以上，资金净流入）',
    sellCondition: 'OBV < 信号线 × 0.99（OBV 低于信号线 1% 以上，资金净流出）',
    neutralCondition: 'OBV 在信号线 ±1% 范围内',
    notes: [
      'OBV 绝对值无参考意义（随累积时间增长），需与信号线比较',
      '用于量价背离检测：价格新高但 OBV 未创新高 → 警示信号',
    ],
    filterMode: 'direction', filterModeDesc: '推荐方向校验',
    filterExamples: [
      { desc: '资金持续净流入才做多', condition: '方向校验（无条件）', applyTo: '双向' },
    ],
    votingTip: '与 MACD、VOL_CONFIRM 搭配，量价配合时提供额外信号确认',
    hardFilterTip: '方向校验：OBV 为 BUY（净流入）才允许做多，为 SELL（净流出）才允许做空',
  },
];

// ── 组件 ─────────────────────────────────────────────────────────────────────
export const StrategyGuide = () => {
  const { t } = useTranslation();

  // ── 指标总览 Tab 列 ───────────────────────────────────────────────────────
  const indicatorDescColumns = [
    { title: t('text.guide.indicatorCode'), dataIndex: 'code',        key: 'code',        width: 120, render: (v: string) => <Tag>{v}</Tag> },
    { title: t('text.guide.indicatorName'), dataIndex: 'nameZh',      key: 'nameZh' },
    { title: t('text.guide.status'),        dataIndex: 'implemented',  key: 'implemented', width: 90,
      render: (v: boolean) => v
        ? <Tag color="success">{t('text.guide.implemented')}</Tag>
        : <Tag color="default">{t('text.guide.notImplemented')}</Tag>,
    },
    { title: t('text.guide.signalLogic'),   dataIndex: 'signal',      key: 'signal',      ellipsis: true },
    { title: t('text.guide.usage'),         dataIndex: 'usage',       key: 'usage' },
  ];

  // ── 参数 Tab 列 ──────────────────────────────────────────────────────────
  const paramsData = Object.entries(INDICATOR_PARAMS).flatMap(([indicator, rows]) =>
    rows.length > 0
      ? rows.map((r) => ({ indicator, ...r }))
      : [{ indicator, param: '-', default: '-', range: '-', desc: t('text.guide.noParams') }]
  );
  const paramsColumns = [
    { title: t('text.guide.indicatorCode'), dataIndex: 'indicator', key: 'indicator', width: 120, render: (v: string) => <Tag>{v}</Tag> },
    { title: t('text.guide.paramName'),     dataIndex: 'param',     key: 'param',     width: 120 },
    { title: t('text.guide.defaultValue'),  dataIndex: 'default',   key: 'default',   width: 90 },
    { title: t('text.guide.range'),         dataIndex: 'range',     key: 'range',     width: 110 },
    { title: t('text.guide.paramDesc'),     dataIndex: 'desc',      key: 'desc' },
  ];

  // ── 策略组合 Tab 列 ──────────────────────────────────────────────────────
  const comboColumns = [
    { title: t('text.guide.combo'),   dataIndex: 'combo',   key: 'combo',   render: (v: string) => <Text strong>{v}</Text> },
    { title: t('text.guide.weights'), dataIndex: 'weights', key: 'weights', width: 140 },
    { title: t('text.guide.scene'),   dataIndex: 'scene',   key: 'scene' },
  ];

  // ── 指标详解 Tab — 三阶段说明 ────────────────────────────────────────────
  const phaseSteps = [
    {
      icon: '①',
      color: '#faad14',
      title: '前置硬性过滤器（Pre-Filter）',
      desc: '先于复合投票执行。任一过滤器失败 → 立即返回 NEUTRAL，跳过所有复合指标计算（节省算力）。',
      sub: [
        '方向校验模式（无条件）：指标不得返回 NEUTRAL，否则否决。之后在阶段三做方向对齐。',
        '数值条件（双向 applyTo=空）：检查阈值条件，不满足则阻断所有方向信号。',
      ],
    },
    {
      icon: '②',
      color: '#1677ff',
      title: '复合投票',
      desc: '仅投票指标参与三桶加权评分（BUY / SELL / NEUTRAL 权重竞争），权重最高且严格领先的桶胜出。',
      sub: [],
    },
    {
      icon: '③',
      color: '#52c41a',
      title: '后置方向校验（Post-Filter）',
      desc: '在已知复合信号方向后执行，仅对 BUY 或 SELL 信号有效。',
      sub: [
        '方向校验模式：复合方向须与过滤器指标方向一致（如 SuperTrend 为 BUY 且复合为 BUY）。',
        '数值条件（方向专属 applyTo=BUY/SELL）：仅在复合信号为对应方向时检查，另一方向不受影响。',
      ],
    },
  ];

  // ── 指标详解 Tab — Collapse 面板 ─────────────────────────────────────────
  const dirTagProps = (dir: IndicatorDetail['signalDir']) => {
    if (dir === '双向')      return { color: 'success', icon: <CheckCircleOutlined /> };
    if (dir === '仅BUY')    return { color: 'gold',    icon: <WarningOutlined /> };
    return                          { color: 'default', icon: <WarningOutlined /> };
  };

  const filterModeLabel: Record<FilterMode, { label: string; color: string }> = {
    'direction':          { label: '推荐：方向校验',       color: 'blue' },
    'value':              { label: '推荐：数值条件',       color: 'orange' },
    'direction-or-value': { label: '方向校验 / 数值条件均可', color: 'cyan' },
    'value-only':         { label: '只能：数值条件',       color: 'red' },
  };

  const collapseItems = INDICATOR_DETAILS.map((ind) => {
    const dtag = dirTagProps(ind.signalDir);
    const fml  = filterModeLabel[ind.filterMode];
    return {
      key: ind.code,
      label: (
        <Space>
          <Tag style={{ fontWeight: 600, minWidth: 110 }}>{ind.code}</Tag>
          <span>{ind.nameZh}</span>
          <Tag color={ind.categoryColor}>{ind.category}</Tag>
          <Tag color={dtag.color} icon={dtag.icon}>{ind.signalDir}</Tag>
        </Space>
      ),
      children: (
        <Row gutter={[24, 16]}>
          {/* 左列：信号逻辑 */}
          <Col xs={24} lg={13}>
            <Text strong style={{ fontSize: 13 }}>信号逻辑</Text>
            <div style={{ marginTop: 8 }}>
              <div style={{ marginBottom: 6 }}>
                <Badge color="green" text={<span><Text type="success">BUY：</Text>{ind.buyCondition}</span>} />
              </div>
              <div style={{ marginBottom: 6 }}>
                <Badge color="red" text={<span><Text type="danger">SELL：</Text>{ind.sellCondition}</span>} />
              </div>
              <div style={{ marginBottom: 8 }}>
                <Badge color="gray" text={<span><Text type="secondary">NEUTRAL：</Text>{ind.neutralCondition}</span>} />
              </div>
              {ind.notes.map((n, i) => (
                <Alert
                  key={i}
                  type={n.startsWith('⚠️') ? 'warning' : 'info'}
                  message={n}
                  showIcon={false}
                  style={{ marginBottom: 6, padding: '4px 10px', fontSize: 12 }}
                />
              ))}
            </div>
            <div style={{ marginTop: 12 }}>
              <Text strong style={{ fontSize: 13 }}>投票参与建议</Text>
              <Paragraph style={{ marginTop: 6, marginBottom: 0, fontSize: 12, color: '#555' }}>
                {ind.votingTip}
              </Paragraph>
            </div>
          </Col>

          {/* 右列：硬性过滤 */}
          <Col xs={24} lg={11}>
            <Space style={{ marginBottom: 8 }}>
              <Text strong style={{ fontSize: 13 }}>硬性过滤配置</Text>
              <Tag color={fml.color} style={{ fontSize: 11 }}>{fml.label}</Tag>
            </Space>
            <Paragraph style={{ fontSize: 12, color: '#555', marginBottom: 10 }}>
              {ind.hardFilterTip}
            </Paragraph>
            {ind.filterExamples.length > 0 && (
              <>
                <Text style={{ fontSize: 12, fontWeight: 600 }}>条件示例：</Text>
                <div style={{ marginTop: 6 }}>
                  {ind.filterExamples.map((ex, i) => (
                    <div
                      key={i}
                      style={{
                        display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                        padding: '5px 10px', marginBottom: 5,
                        background: '#fafafa', border: '1px solid #f0f0f0', borderRadius: 6,
                      }}
                    >
                      <div>
                        <Text style={{ fontSize: 11, color: '#666' }}>{ex.desc}</Text>
                        <br />
                        <Text code style={{ fontSize: 11 }}>{ex.condition}</Text>
                      </div>
                      <Tag
                        color={ex.applyTo === '双向' ? 'default' : ex.applyTo === '仅买入' ? 'gold' : 'volcano'}
                        style={{ fontSize: 10, marginLeft: 8, flexShrink: 0 }}
                      >
                        {ex.applyTo}
                      </Tag>
                    </div>
                  ))}
                </div>
              </>
            )}
          </Col>
        </Row>
      ),
    };
  });

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
        defaultActiveKey="details"
        items={[
          // ──────────────────── Tab 1: 指标详解 ────────────────────────────
          {
            key: 'details',
            label: <span><InfoCircleOutlined /> {t('text.guide.tabDetails')}</span>,
            children: (
              <div>
                {/* 三阶段信号流程说明 */}
                <Card
                  size="small"
                  title={<Text strong>系统信号生成流程（三阶段）</Text>}
                  style={{ marginBottom: 16 }}
                >
                  <Row gutter={16}>
                    {phaseSteps.map((step) => (
                      <Col xs={24} md={8} key={step.icon}>
                        <Card
                          size="small"
                          style={{ borderLeft: `3px solid ${step.color}`, height: '100%' }}
                          bodyStyle={{ padding: '10px 14px' }}
                        >
                          <Space style={{ marginBottom: 6 }}>
                            <Tag color={step.color} style={{ fontWeight: 700, fontSize: 14 }}>{step.icon}</Tag>
                            <Text strong style={{ fontSize: 13 }}>{step.title}</Text>
                          </Space>
                          <Paragraph style={{ fontSize: 12, marginBottom: step.sub.length > 0 ? 8 : 0 }}>
                            {step.desc}
                          </Paragraph>
                          {step.sub.map((s, i) => (
                            <div key={i} style={{ fontSize: 11, color: '#666', paddingLeft: 8, marginBottom: 4, borderLeft: '2px solid #e0e0e0' }}>
                              {s}
                            </div>
                          ))}
                        </Card>
                      </Col>
                    ))}
                  </Row>
                  <div style={{ marginTop: 12, padding: '8px 12px', background: '#f6f6f6', borderRadius: 6, fontSize: 12, color: '#555' }}>
                    💡 <Text strong>applyTo 选择原则：</Text>
                    不选（双向）→ 投票前门槛（如 ADX 强度、ATR 波动性）；
                    选仅买入/卖出 → 投票后方向专属过滤（如 RSI 超卖仅限买入，RSI 超买仅限卖出）
                  </div>
                </Card>

                {/* 信号方向图例 */}
                <Space style={{ marginBottom: 12 }}>
                  <Text type="secondary" style={{ fontSize: 12 }}>信号方向：</Text>
                  <Tag color="success" icon={<CheckCircleOutlined />}>双向</Tag>
                  <Text type="secondary" style={{ fontSize: 12 }}>产生 BUY + SELL</Text>
                  <Tag color="gold" icon={<WarningOutlined />}>仅BUY</Tag>
                  <Text type="secondary" style={{ fontSize: 12 }}>只产生 BUY（超卖/趋势判断）</Text>
                  <Tag color="default" icon={<WarningOutlined />}>仅NEUTRAL</Tag>
                  <Text type="secondary" style={{ fontSize: 12 }}>无方向信号（波动率指标）</Text>
                </Space>

                {/* 指标详解 Collapse */}
                <Collapse
                  items={collapseItems}
                  defaultActiveKey={['SUPERTREND', 'ADX', 'RSI', 'ATR']}
                  size="small"
                />
              </div>
            ),
          },

          // ──────────────────── Tab 2: 指标总览 ────────────────────────────
          {
            key: 'indicators',
            label: <span><BookOutlined /> {t('text.guide.tabIndicators')}</span>,
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

          // ──────────────────── Tab 3: 参数配置 ────────────────────────────
          {
            key: 'params',
            label: <span><ApiOutlined /> {t('text.guide.tabParams')}</span>,
            children: (
              <Card size="small">
                <Paragraph type="secondary" style={{ marginBottom: 16 }}>
                  {t('text.guide.tabParamsDesc')} {t('text.guide.tabParamsNote')}
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

          // ──────────────────── Tab 4: 策略组合 ────────────────────────────
          {
            key: 'combinations',
            label: <span><ClusterOutlined /> {t('text.guide.tabCombos')}</span>,
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
