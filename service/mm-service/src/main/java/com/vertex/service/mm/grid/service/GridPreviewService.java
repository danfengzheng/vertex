package com.vertex.service.mm.grid.service;

import com.vertex.service.mm.grid.dto.GridPreviewRequest;
import com.vertex.service.mm.grid.vo.GridPreviewVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 网格策略预览计算服务。
 * <p>纯计算，不查数据库、不下单。用于前端配置阶段的实时估算。</p>
 */
@Slf4j
@Service
public class GridPreviewService {

    private static final BigDecimal DEFAULT_FEE_RATE = new BigDecimal("0.0005");
    private static final BigDecimal DEFAULT_MMR = new BigDecimal("0.005"); // 维持保证金率
    private static final int MONEY_SCALE = 4;
    private static final int PCT_SCALE = 6;

    public GridPreviewVO preview(GridPreviewRequest req) {
        // ── 参数默认值 ────────────────────────────────────────────
        String side = req.getSide() == null ? "BOTH" : req.getSide().toUpperCase();
        String modeStr = req.getMode() == null ? "ARITHMETIC" : req.getMode().toUpperCase();
        GridPriceCalculator.GridMode mode = GridPriceCalculator.GridMode.valueOf(modeStr);
        BigDecimal feeRate = req.getFeeRate() == null ? DEFAULT_FEE_RATE : req.getFeeRate();
        BigDecimal mmr = req.getMaintenanceMarginRate() == null ? DEFAULT_MMR : req.getMaintenanceMarginRate();
        int leverage = req.getLeverage() == null || req.getLeverage() <= 0 ? 1 : req.getLeverage();
        BigDecimal qtyPerGrid = req.getQtyPerGrid() == null ? BigDecimal.ZERO : req.getQtyPerGrid();

        // ── 校验 & 快速失败（返回带 warnings 的空壳）────────────────
        List<String> warnings = new ArrayList<>();
        if (!validateBasics(req, warnings)) {
            return GridPreviewVO.builder()
                    .basics(buildBasics(req, side, modeStr, feeRate))
                    .warnings(warnings)
                    .build();
        }

        // ── 生成价位数组 ──────────────────────────────────────────
        BigDecimal[] prices = GridPriceCalculator.calculate(
                req.getLowerPrice(), req.getUpperPrice(), req.getGridCount(), mode);

        int currentIdx = GridPriceCalculator.findClosestIdx(prices, req.getCurrentPrice());
        int totalPoints = prices.length; // count + 1

        // 上下方可用格数（受 side 限制）
        boolean allowLong = "LONG_ONLY".equals(side) || "BOTH".equals(side);
        boolean allowShort = "SHORT_ONLY".equals(side) || "BOTH".equals(side);
        int buySlots = allowLong ? currentIdx : 0;
        int sellSlots = allowShort ? (totalPoints - 1 - currentIdx) : 0;

        // ── 分布信息 ──────────────────────────────────────────────
        double stepPctDouble = mode == GridPriceCalculator.GridMode.GEOMETRIC
                ? GridPriceCalculator.geometricStepPct(req.getLowerPrice(), req.getUpperPrice(), req.getGridCount())
                : GridPriceCalculator.arithmeticAvgStepPct(prices);
        double netProfitPct = GridPriceCalculator.netProfitPct(stepPctDouble, feeRate.doubleValue());
        BigDecimal avgAbsStep = req.getUpperPrice().subtract(req.getLowerPrice())
                .divide(BigDecimal.valueOf(req.getGridCount()), MONEY_SCALE, RoundingMode.HALF_UP);

        GridPreviewVO.Distribution distribution = GridPreviewVO.Distribution.builder()
                .stepPricesFirstFew(headFew(prices, 5))
                .stepPricesLastFew(tailFew(prices, 5))
                .avgStepAbsolute(avgAbsStep)
                .stepPct(round(stepPctDouble, PCT_SCALE))
                .netProfitPerGridPct(round(netProfitPct, PCT_SCALE))
                .build();

        // ── 多头满仓投影（下方 buySlots 格全部成交后）────────────
        GridPreviewVO.SideProjection longSide = buildSideProjection(
                prices, 0, currentIdx, buySlots, qtyPerGrid, leverage, mmr, /*isLong=*/ true);

        // ── 空头满仓投影（上方 sellSlots 格全部成交后）────────────
        GridPreviewVO.SideProjection shortSide = buildSideProjection(
                prices, currentIdx + 1, totalPoints, sellSlots, qtyPerGrid, leverage, mmr, /*isLong=*/ false);

        // ── 汇总 ─────────────────────────────────────────────────
        BigDecimal totalMargin = safeAdd(longSide.getRequiredMargin(), shortSide.getRequiredMargin());
        BigDecimal totalNotional = safeAdd(longSide.getMaxNotional(), shortSide.getMaxNotional());

        // ── 单轮净利润估算 ────────────────────────────────────────
        //  以当前价 × qty × netPct% 作为一格的美元净利润
        BigDecimal netUsdtPerRound = req.getCurrentPrice()
                .multiply(qtyPerGrid)
                .multiply(BigDecimal.valueOf(netProfitPct / 100.0))
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        GridPreviewVO.RoundProfit roundProfit = GridPreviewVO.RoundProfit.builder()
                .grossPct(round(stepPctDouble, PCT_SCALE))
                .netPct(round(netProfitPct, PCT_SCALE))
                .netUsdtPerRound(netUsdtPerRound)
                .build();

        // ── Warnings ─────────────────────────────────────────────
        addRuntimeWarnings(req, side, netProfitPct, qtyPerGrid, longSide, shortSide, warnings);

        // ── 窗口覆盖率（当传入 windowSizePerSide 时计算）──────────
        GridPreviewVO.WindowCoverage windowCoverage = null;
        if (req.getWindowSizePerSide() != null && req.getWindowSizePerSide() > 0) {
            int ws = req.getWindowSizePerSide();
            BigDecimal covAbs = avgAbsStep.multiply(BigDecimal.valueOf(ws))
                    .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            BigDecimal covPct = BigDecimal.valueOf(stepPctDouble * ws)
                    .setScale(PCT_SCALE, RoundingMode.HALF_UP);
            String note = null;
            if (covPct.doubleValue() < 1.0) {
                note = "覆盖 < 1%，价格急涨/急跌时容易滑出窗口，建议增大 windowSize";
            } else if (covPct.doubleValue() > 10.0) {
                note = "覆盖 > 10%，同侧同时挂单多，rate limit 压力大，可考虑减小";
            }
            windowCoverage = GridPreviewVO.WindowCoverage.builder()
                    .stepAbsolute(avgAbsStep)
                    .stepPct(round(stepPctDouble, PCT_SCALE))
                    .windowSize(ws)
                    .sideCoverageAbsolute(covAbs)
                    .sideCoveragePct(covPct)
                    .note(note)
                    .build();
        }

        return GridPreviewVO.builder()
                .basics(buildBasics(req, side, modeStr, feeRate))
                .distribution(distribution)
                .windowCoverage(windowCoverage)
                .longSide(longSide)
                .shortSide(shortSide)
                .totals(GridPreviewVO.Totals.builder()
                        .totalRequiredMargin(totalMargin)
                        .maxAggregateNotional(totalNotional)
                        .build())
                .roundProfit(roundProfit)
                .warnings(warnings)
                .build();
    }

    // ─────────────────────────────────────────────────────────────
    // helpers
    // ─────────────────────────────────────────────────────────────

    private boolean validateBasics(GridPreviewRequest req, List<String> warnings) {
        if (req.getLowerPrice() == null || req.getUpperPrice() == null) {
            warnings.add("请填写区间下限与上限");
            return false;
        }
        if (req.getLowerPrice().signum() <= 0 || req.getUpperPrice().signum() <= 0) {
            warnings.add("上下限必须为正数");
            return false;
        }
        if (req.getUpperPrice().compareTo(req.getLowerPrice()) <= 0) {
            warnings.add("区间无效：上限必须大于下限");
            return false;
        }
        if (req.getGridCount() == null || req.getGridCount() < 2) {
            warnings.add("格数至少为 2");
            return false;
        }
        if (req.getGridCount() > 500) {
            warnings.add("格数 > 500，rate limit 压力大");
        }
        if (req.getCurrentPrice() == null) {
            warnings.add("请填写当前价（用于估算多空槽位与保证金）");
            return false;
        }
        if (req.getQtyPerGrid() == null || req.getQtyPerGrid().signum() <= 0) {
            warnings.add("请填写每格数量");
            return false;
        }
        if (req.getLeverage() == null || req.getLeverage() <= 0) {
            warnings.add("请填写杠杆倍数");
            return false;
        }
        return true;
    }

    private void addRuntimeWarnings(GridPreviewRequest req, String side, double netProfitPct,
                                     BigDecimal qtyPerGrid,
                                     GridPreviewVO.SideProjection longSide,
                                     GridPreviewVO.SideProjection shortSide,
                                     List<String> warnings) {
        if (netProfitPct < 0.05) {
            warnings.add("扣费后每格净利润 < 0.05%，接近盈亏平衡");
        }
        BigDecimal singleNotional = qtyPerGrid.multiply(req.getLowerPrice());
        if (singleNotional.compareTo(new BigDecimal("5")) < 0) {
            warnings.add("单格名义 " + singleNotional.setScale(2, RoundingMode.HALF_UP)
                    + " USDT < 5，可能低于交易所最小下单");
        }
        // 强平风险检查
        if (longSide.getEstLiquidationPrice() != null) {
            BigDecimal threshold = req.getLowerPrice().multiply(new BigDecimal("0.9"));
            if (longSide.getEstLiquidationPrice().compareTo(threshold) > 0) {
                warnings.add("多仓强平价 " + longSide.getEstLiquidationPrice().setScale(2, RoundingMode.HALF_UP)
                        + " 接近区间下限，跌破网格底端将强平");
            }
        }
        if (shortSide.getEstLiquidationPrice() != null && shortSide.getEstLiquidationPrice().signum() > 0) {
            BigDecimal threshold = req.getUpperPrice().multiply(new BigDecimal("1.1"));
            if (shortSide.getEstLiquidationPrice().compareTo(threshold) < 0) {
                warnings.add("空仓强平价 " + shortSide.getEstLiquidationPrice().setScale(2, RoundingMode.HALF_UP)
                        + " 接近区间上限，涨破网格顶端将强平");
            }
        }
        if ("BOTH".equals(side)) {
            warnings.add("提示：BOTH 模式需 Binance Hedge Mode（双向持仓），请在币安账户设置中开启");
        }
    }

    private GridPreviewVO.Basics buildBasics(GridPreviewRequest req, String side, String mode, BigDecimal feeRate) {
        return GridPreviewVO.Basics.builder()
                .symbol(req.getSymbol())
                .side(side)
                .mode(mode)
                .lowerPrice(req.getLowerPrice())
                .upperPrice(req.getUpperPrice())
                .gridCount(req.getGridCount())
                .qtyPerGrid(req.getQtyPerGrid())
                .currentPrice(req.getCurrentPrice())
                .leverage(req.getLeverage())
                .feeRate(feeRate)
                .build();
    }

    /**
     * 累加一段价格区间的名义价值、数量、成本，计算保证金与强平价。
     *
     * <p><b>语义</b>：这里计算的是"最坏情况最大暴露" —— 假设 currentPrice 走到区间的
     * 一端，路径上所有 slots 依次成交且尚未反手平仓时的累计头寸。滑动窗口
     * (window_size) 只限制"同一时刻挂着的活单数"，不改变最终可以累计的头寸，
     * 因此 preview 不需要 window_size 作为入参。</p>
     *
     * <p><b>不包含反手 CLOSE 单的影响</b>：反手 CLOSE 单一旦成交就释放对应 gridIndex
     * 的 POSITION_HELD 状态，此时 preview 展示的"最大头寸"会被反向减少；这是
     * 保守估计（上界）。</p>
     *
     * @param prices        全量价位数组
     * @param fromIdx       起始下标（含）
     * @param toIdx         结束下标（不含）
     * @param slots         有效槽位数（已被 side / 位置过滤过）
     * @param qtyPerGrid    每格数量
     * @param leverage      杠杆
     * @param mmr           维持保证金率（如 0.005）
     * @param isLong        是否为多头方向
     */
    private GridPreviewVO.SideProjection buildSideProjection(
            BigDecimal[] prices, int fromIdx, int toIdx, int slots,
            BigDecimal qtyPerGrid, int leverage, BigDecimal mmr, boolean isLong) {

        if (slots <= 0) {
            return GridPreviewVO.SideProjection.builder()
                    .slots(0)
                    .maxQty(BigDecimal.ZERO)
                    .maxNotional(BigDecimal.ZERO)
                    .avgPrice(BigDecimal.ZERO)
                    .requiredMargin(BigDecimal.ZERO)
                    .estLiquidationPrice(BigDecimal.ZERO)
                    .build();
        }

        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalNotional = BigDecimal.ZERO;

        int used = 0;
        for (int i = fromIdx; i < toIdx && used < slots; i++, used++) {
            BigDecimal price = prices[i];
            BigDecimal notional = price.multiply(qtyPerGrid);
            totalNotional = totalNotional.add(notional);
            totalQty = totalQty.add(qtyPerGrid);
        }

        BigDecimal avgPrice = totalQty.signum() == 0
                ? BigDecimal.ZERO
                : totalNotional.divide(totalQty, MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal margin = totalNotional
                .divide(BigDecimal.valueOf(leverage), MONEY_SCALE, RoundingMode.HALF_UP);

        // 强平价（隔离仓简化公式）
        // 多仓: liq ≈ avg × (1 - 1/lev + mmr)
        // 空仓: liq ≈ avg × (1 + 1/lev - mmr)
        BigDecimal invLev = BigDecimal.ONE.divide(BigDecimal.valueOf(leverage), 8, RoundingMode.HALF_UP);
        BigDecimal liq;
        if (isLong) {
            liq = avgPrice.multiply(BigDecimal.ONE.subtract(invLev).add(mmr))
                    .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        } else {
            liq = avgPrice.multiply(BigDecimal.ONE.add(invLev).subtract(mmr))
                    .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }

        return GridPreviewVO.SideProjection.builder()
                .slots(slots)
                .maxQty(totalQty.setScale(8, RoundingMode.HALF_UP))
                .maxNotional(totalNotional.setScale(MONEY_SCALE, RoundingMode.HALF_UP))
                .avgPrice(avgPrice)
                .requiredMargin(margin)
                .estLiquidationPrice(liq)
                .build();
    }

    private List<BigDecimal> headFew(BigDecimal[] arr, int n) {
        int len = Math.min(n, arr.length);
        return new ArrayList<>(Arrays.asList(arr).subList(0, len));
    }

    private List<BigDecimal> tailFew(BigDecimal[] arr, int n) {
        int len = Math.min(n, arr.length);
        return new ArrayList<>(Arrays.asList(arr).subList(arr.length - len, arr.length));
    }

    private BigDecimal safeAdd(BigDecimal a, BigDecimal b) {
        BigDecimal aa = a == null ? BigDecimal.ZERO : a;
        BigDecimal bb = b == null ? BigDecimal.ZERO : b;
        return aa.add(bb);
    }

    private BigDecimal round(double val, int scale) {
        return BigDecimal.valueOf(val).setScale(scale, RoundingMode.HALF_UP);
    }
}
