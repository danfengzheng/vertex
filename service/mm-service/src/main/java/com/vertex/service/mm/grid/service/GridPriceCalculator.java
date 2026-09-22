package com.vertex.service.mm.grid.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 网格价格计算工具类。
 * <p>
 * 支持等差 (ARITHMETIC) 与等比 (GEOMETRIC) 两种模式，
 * 用于生成静态价位数组、计算利润率、快速定位 currentPrice 所处网格。
 * </p>
 *
 * <h3>等差 Arithmetic</h3>
 * <pre>
 *   step = (upper - lower) / count
 *   price[i] = lower + i * step,  i ∈ [0, count]
 * </pre>
 *
 * <h3>等比 Geometric</h3>
 * <pre>
 *   ratio = (upper / lower) ^ (1 / count)
 *   price[i] = lower * ratio ^ i,  i ∈ [0, count]
 * </pre>
 */
public final class GridPriceCalculator {

    public enum GridMode {
        ARITHMETIC, GEOMETRIC
    }

    private static final int PRICE_SCALE = 8;
    private static final int PCT_SCALE = 6;

    private GridPriceCalculator() {
    }

    // ─────────────────────────────────────────────────────────────
    // 主接口
    // ─────────────────────────────────────────────────────────────

    /**
     * 生成所有网格价格（升序，长度 = count + 1）。
     */
    public static BigDecimal[] calculate(
            BigDecimal lower, BigDecimal upper, int count, GridMode mode) {
        validate(lower, upper, count, mode);

        BigDecimal[] prices = new BigDecimal[count + 1];
        if (mode == GridMode.ARITHMETIC) {
            BigDecimal step = upper.subtract(lower)
                    .divide(BigDecimal.valueOf(count), PRICE_SCALE, RoundingMode.HALF_UP);
            for (int i = 0; i <= count; i++) {
                prices[i] = lower.add(step.multiply(BigDecimal.valueOf(i)))
                        .setScale(PRICE_SCALE, RoundingMode.HALF_UP);
            }
        } else {
            double ratio = Math.pow(
                    upper.doubleValue() / lower.doubleValue(),
                    1.0 / count
            );
            double lowerD = lower.doubleValue();
            for (int i = 0; i <= count; i++) {
                double v = lowerD * Math.pow(ratio, i);
                prices[i] = BigDecimal.valueOf(v).setScale(PRICE_SCALE, RoundingMode.HALF_UP);
            }
            // 强制首末对齐 (double 精度补偿)
            prices[0] = lower.setScale(PRICE_SCALE, RoundingMode.HALF_UP);
            prices[count] = upper.setScale(PRICE_SCALE, RoundingMode.HALF_UP);
        }
        return prices;
    }

    /**
     * 等比模式每格恒定利润率（%），如 0.5 表示 0.5%。
     */
    public static double geometricStepPct(BigDecimal lower, BigDecimal upper, int count) {
        validate(lower, upper, count, GridMode.GEOMETRIC);
        double ratio = Math.pow(upper.doubleValue() / lower.doubleValue(), 1.0 / count);
        return (ratio - 1.0) * 100.0;
    }

    /**
     * 等差模式的平均利润率（%），各格利润率的算术平均。
     */
    public static double arithmeticAvgStepPct(BigDecimal[] prices) {
        if (prices == null || prices.length < 2) return 0.0;
        double sum = 0.0;
        int n = prices.length - 1;
        for (int i = 0; i < n; i++) {
            double base = prices[i].doubleValue();
            if (base <= 0) continue;
            sum += (prices[i + 1].doubleValue() - base) / base * 100.0;
        }
        return sum / n;
    }

    /**
     * 找到 prices[] 中距离 target 最近的下标。
     */
    public static int findClosestIdx(BigDecimal[] prices, BigDecimal target) {
        if (prices == null || prices.length == 0) return -1;
        int lo = 0, hi = prices.length - 1;
        // 二分：prices 升序
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (prices[mid].compareTo(target) < 0) lo = mid + 1;
            else hi = mid;
        }
        // 比较相邻，取更近的
        if (lo > 0) {
            BigDecimal d1 = prices[lo].subtract(target).abs();
            BigDecimal d0 = target.subtract(prices[lo - 1]).abs();
            if (d0.compareTo(d1) < 0) return lo - 1;
        }
        return lo;
    }

    /**
     * 单格净利润率（扣双向手续费）（%）。
     */
    public static double netProfitPct(double grossStepPct, double feeRate) {
        // 双向手续费 = feeRate * 2, 单位百分比
        return grossStepPct - feeRate * 100.0 * 2;
    }

    // ─────────────────────────────────────────────────────────────
    // 内部
    // ─────────────────────────────────────────────────────────────

    private static void validate(BigDecimal lower, BigDecimal upper, int count, GridMode mode) {
        if (lower == null || upper == null) {
            throw new IllegalArgumentException("lower/upper must not be null");
        }
        if (lower.signum() <= 0 || upper.signum() <= 0) {
            throw new IllegalArgumentException("lower/upper must be positive");
        }
        if (upper.compareTo(lower) <= 0) {
            throw new IllegalArgumentException("upper must be > lower");
        }
        if (count < 2) {
            throw new IllegalArgumentException("gridCount must be >= 2");
        }
        if (mode == null) {
            throw new IllegalArgumentException("mode must not be null");
        }
    }

    /**
     * 圆整为标准比例的辅助方法。
     */
    public static BigDecimal roundPct(double pct) {
        return BigDecimal.valueOf(pct).setScale(PCT_SCALE, RoundingMode.HALF_UP);
    }
}
