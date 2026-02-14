package com.vertex.model.entity.strategy;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 技术指标类型
 */
@Getter
@AllArgsConstructor
public enum IndicatorType {

    MA("MA", "简单移动平均线"),
    EMA("EMA", "指数移动平均线"),
    RSI("RSI", "相对强弱指标"),
    MACD("MACD", "MACD指标"),
    BOLL("BOLL", "布林带"),
    KDJ("KDJ", "随机指标"),
    ATR("ATR", "平均真实波幅"),
    VWAP("VWAP", "成交量加权平均价"),
    STOCH_RSI("STOCH_RSI", "随机RSI"),
    WR("WR", "威廉指标"),
    SAR("SAR", "抛物线转向指标"),
    ADX("ADX", "平均趋向指数"),
    SUPERTREND("SUPERTREND", "超级趋势指标"),
    VOL_CONFIRM("VOL_CONFIRM", "成交量确认"),
    OBV("OBV", "能量潮指标");

    private final String code;
    private final String description;

    public static IndicatorType fromCode(String code) {
        for (IndicatorType type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown IndicatorType code: " + code);
    }
}
