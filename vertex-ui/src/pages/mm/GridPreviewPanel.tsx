import { useEffect, useState } from 'react';
import type { CSSProperties, ReactNode } from 'react';
import { mmGridApi, type GridPreviewRequest, type GridPreviewVO } from '../../api/mmGrid';

/**
 * 网格策略预览面板。
 *
 * 使用方式：把 <GridPreviewPanel req={...} /> 放在 GridStrategyForm 中，
 * req 的字段随表单变化更新，本组件内部会 debounce 300ms 调用后端 /mm/grid/preview。
 */

interface Props {
  req: GridPreviewRequest;
  /** 手续费率默认 0.0005（0.05%），可覆盖 */
  defaultFeeRate?: number;
}

const num = (v?: number | null, d = 2): string =>
  v == null || isNaN(v) ? '-' : Number(v).toFixed(d);

const pct = (v?: number | null): string => (v == null || isNaN(v) ? '-' : `${Number(v).toFixed(4)}%`);

export default function GridPreviewPanel({ req, defaultFeeRate = 0.0005 }: Props) {
  const [preview, setPreview] = useState<GridPreviewVO | null>(null);
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    // 关键字段缺失就不请求
    if (!req.lowerPrice || !req.upperPrice || !req.gridCount ||
        !req.qtyPerGrid || !req.currentPrice || !req.leverage) {
      setPreview(null);
      return;
    }

    const timer = setTimeout(async () => {
      try {
        setLoading(true);
        setErr(null);
        const res = await mmGridApi.preview({
          ...req,
          feeRate: req.feeRate ?? defaultFeeRate,
        });
        if (res.code === 200) {
          setPreview(res.data);
        } else {
          setErr(res.message || '预览失败');
        }
      } catch (e: any) {
        setErr(e?.message || '网络异常');
      } finally {
        setLoading(false);
      }
    }, 300); // debounce

    return () => clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    req.symbol, req.side, req.mode,
    req.lowerPrice, req.upperPrice, req.gridCount, req.qtyPerGrid,
    req.currentPrice, req.leverage, req.feeRate,
  ]);

  if (!preview && !loading && !err) {
    return (
      <div style={styles.panel}>
        <div style={styles.title}>网格预览</div>
        <div style={styles.hint}>填写区间 / 格数 / 每格数量 / 当前价 / 杠杆后自动显示预览</div>
      </div>
    );
  }

  return (
    <div style={styles.panel}>
      <div style={styles.title}>
        网格预览 {loading && <span style={styles.loading}>· 加载中</span>}
      </div>

      {err && <div style={styles.err}>{err}</div>}

      {preview && (
        <>
          {/* 基础 */}
          <Section title="基础">
            <Row label="模式" value={
              preview.basics?.mode === 'GEOMETRIC' ? '等比' :
              preview.basics?.mode === 'ARITHMETIC' ? '等差' : '-'
            } />
            <Row label="方向" value={
              preview.basics?.side === 'LONG_ONLY' ? '只做多' :
              preview.basics?.side === 'SHORT_ONLY' ? '只做空' :
              preview.basics?.side === 'BOTH' ? '双向' : '-'
            } />
            <Row label="区间"
                 value={`${num(preview.basics?.lowerPrice)} ~ ${num(preview.basics?.upperPrice)}`} />
            <Row label="格数" value={String(preview.basics?.gridCount ?? '-')} />
            <Row label="每格数量" value={num(preview.basics?.qtyPerGrid, 6)} />
            <Row label="当前价" value={num(preview.basics?.currentPrice)} />
            <Row label="杠杆" value={`${preview.basics?.leverage ?? '-'}x`} />
          </Section>

          {/* 分布 */}
          <Section title="价格分布">
            <Row label="平均绝对步长" value={`${num(preview.distribution?.avgStepAbsolute)} USDT`} />
            <Row label="每格价差%" value={pct(preview.distribution?.stepPct)} />
            <Row label="每格净利润%（扣费）"
                 value={pct(preview.distribution?.netProfitPerGridPct)}
                 highlight={
                   (preview.distribution?.netProfitPerGridPct ?? 0) < 0.05 ? 'warn' : 'ok'
                 } />
          </Section>

          {/* 窗口覆盖率 */}
          {preview.windowCoverage && (
            <Section title="窗口覆盖">
              <Row label="单档步长（USDT）" value={num(preview.windowCoverage.stepAbsolute)} />
              <Row label="单档步长%" value={pct(preview.windowCoverage.stepPct)} />
              <Row label="单侧档数" value={String(preview.windowCoverage.windowSize ?? '-')} />
              <Row label="单侧覆盖价（USDT）"
                   value={`±${num(preview.windowCoverage.sideCoverageAbsolute)}`}
                   highlight="strong" />
              <Row label="单侧覆盖%"
                   value={`±${num(preview.windowCoverage.sideCoveragePct, 4)}%`}
                   highlight="strong" />
              {preview.windowCoverage.note && (
                <div style={{ ...styles.warnItem, marginTop: 6 }}>💡 {preview.windowCoverage.note}</div>
              )}
            </Section>
          )}

          {/* 多头 */}
          {(preview.longSide?.slots ?? 0) > 0 && (
            <Section title={`多头满仓（${preview.longSide?.slots} 格）`}>
              <Row label="数量" value={num(preview.longSide?.maxQty, 6)} />
              <Row label="名义价值" value={`${num(preview.longSide?.maxNotional)} USDT`} />
              <Row label="均价" value={num(preview.longSide?.avgPrice)} />
              <Row label="保证金" value={`${num(preview.longSide?.requiredMargin)} USDT`} />
              <Row label="强平价"
                   value={num(preview.longSide?.estLiquidationPrice)}
                   highlight={isLiqDanger(preview, 'long') ? 'danger' : 'ok'} />
            </Section>
          )}

          {/* 空头 */}
          {(preview.shortSide?.slots ?? 0) > 0 && (
            <Section title={`空头满仓（${preview.shortSide?.slots} 格）`}>
              <Row label="数量" value={num(preview.shortSide?.maxQty, 6)} />
              <Row label="名义价值" value={`${num(preview.shortSide?.maxNotional)} USDT`} />
              <Row label="均价" value={num(preview.shortSide?.avgPrice)} />
              <Row label="保证金" value={`${num(preview.shortSide?.requiredMargin)} USDT`} />
              <Row label="强平价"
                   value={num(preview.shortSide?.estLiquidationPrice)}
                   highlight={isLiqDanger(preview, 'short') ? 'danger' : 'ok'} />
            </Section>
          )}

          {/* 汇总 */}
          <Section title="汇总">
            <Row label="双向保证金合计"
                 value={`${num(preview.totals?.totalRequiredMargin)} USDT`}
                 highlight="strong" />
            <Row label="名义价值合计" value={`${num(preview.totals?.maxAggregateNotional)} USDT`} />
          </Section>

          {/* 单轮利润 */}
          <Section title="单轮利润">
            <Row label="毛利润率" value={pct(preview.roundProfit?.grossPct)} />
            <Row label="净利润率" value={pct(preview.roundProfit?.netPct)} />
            <Row label="每轮净利" value={`${num(preview.roundProfit?.netUsdtPerRound, 4)} USDT`} />
          </Section>

          {/* Warnings */}
          {preview.warnings && preview.warnings.length > 0 && (
            <div style={styles.warnBox}>
              <div style={styles.warnTitle}>⚠️ 提示 / 警告</div>
              <ul style={{ margin: 0, paddingLeft: 18 }}>
                {preview.warnings.map((w, i) => (
                  <li key={i} style={styles.warnItem}>{w}</li>
                ))}
              </ul>
            </div>
          )}
        </>
      )}
    </div>
  );
}

// ─── 小组件 ─────────────────────────────────────────────

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div style={styles.section}>
      <div style={styles.sectionTitle}>{title}</div>
      {children}
    </div>
  );
}

function Row({ label, value, highlight }: {
  label: string;
  value: ReactNode;
  highlight?: 'ok' | 'warn' | 'danger' | 'strong';
}) {
  const valStyle: CSSProperties = {
    ...styles.rowValue,
    ...(highlight === 'warn' ? { color: '#d97706' } : {}),
    ...(highlight === 'danger' ? { color: '#dc2626', fontWeight: 600 } : {}),
    ...(highlight === 'strong' ? { fontWeight: 600 } : {}),
  };
  return (
    <div style={styles.row}>
      <span style={styles.rowLabel}>{label}</span>
      <span style={valStyle}>{value}</span>
    </div>
  );
}

// ─── helpers ────────────────────────────────────────────

function isLiqDanger(p: GridPreviewVO, side: 'long' | 'short'): boolean {
  const lower = p.basics?.lowerPrice ?? 0;
  const upper = p.basics?.upperPrice ?? 0;
  if (side === 'long') {
    const liq = p.longSide?.estLiquidationPrice ?? 0;
    return liq > lower * 0.9;
  }
  const liq = p.shortSide?.estLiquidationPrice ?? 0;
  return liq > 0 && liq < upper * 1.1;
}

// ─── 样式 ─────────────────────────────────────────────────

const styles: Record<string, CSSProperties> = {
  panel: {
    border: '1px solid #e5e7eb',
    borderRadius: 8,
    padding: 16,
    background: '#fafafa',
    minWidth: 320,
    fontSize: 13,
  },
  title: { fontWeight: 600, fontSize: 15, marginBottom: 12 },
  loading: { color: '#6b7280', fontWeight: 400, fontSize: 12 },
  hint: { color: '#6b7280', fontSize: 12 },
  err: { color: '#dc2626', padding: 8, background: '#fef2f2', borderRadius: 4, marginBottom: 8 },
  section: { marginBottom: 12 },
  sectionTitle: {
    fontWeight: 600,
    fontSize: 12,
    color: '#6b7280',
    borderBottom: '1px solid #e5e7eb',
    paddingBottom: 4,
    marginBottom: 6,
    textTransform: 'uppercase',
    letterSpacing: 0.5,
  },
  row: { display: 'flex', justifyContent: 'space-between', padding: '3px 0' },
  rowLabel: { color: '#374151' },
  rowValue: { color: '#111827', fontFamily: 'ui-monospace, SFMono-Regular, monospace' },
  warnBox: {
    marginTop: 12,
    padding: 10,
    background: '#fffbeb',
    border: '1px solid #fde68a',
    borderRadius: 6,
  },
  warnTitle: { fontWeight: 600, marginBottom: 6, color: '#92400e' },
  warnItem: { color: '#92400e', marginBottom: 3 },
};
