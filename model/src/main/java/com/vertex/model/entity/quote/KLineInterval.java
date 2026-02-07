package com.vertex.model.entity.quote;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * K线周期枚举（不含秒级别）
 */
@Getter
@AllArgsConstructor
public enum KLineInterval {

    M1("1m", 60_000L),
    M3("3m", 180_000L),
    M5("5m", 300_000L),
    M15("15m", 900_000L),
    M30("30m", 1_800_000L),
    H1("1h", 3_600_000L),
    H2("2h", 7_200_000L),
    H4("4h", 14_400_000L),
    H6("6h", 21_600_000L),
    H8("8h", 28_800_000L),
    H12("12h", 43_200_000L),
    D1("1d", 86_400_000L),
    D3("3d", 259_200_000L),
    W1("1w", 604_800_000L),
    MN1("1M", 2_592_000_000L);

    /** 通用标识（如 1m, 1h, 1d） */
    private final String code;

    /** 周期对应的毫秒数（MN1 为近似值） */
    private final long millis;

    /**
     * 根据 code 查找枚举
     */
    public static KLineInterval fromCode(String code) {
        for (KLineInterval interval : values()) {
            if (interval.code.equals(code)) {
                return interval;
            }
        }
        throw new IllegalArgumentException("Unknown KLineInterval code: " + code);
    }
}
