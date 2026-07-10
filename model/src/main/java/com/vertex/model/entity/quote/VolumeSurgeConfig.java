package com.vertex.model.entity.quote;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 币安现货「成交量暴增」扫描器动态配置（单行表，id=1）。
 * <p>
 * 与 {@code VolumeSurgeProperties}（yaml，基础设施参数）分工：
 * 本实体存储运行时可热切换的业务参数，UI 可编辑；
 * VolumeSurgeProperties 只放不能热改的部分（apiUrl、并发数、权重软限、初始延迟）。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("volume_surge_config")
public class VolumeSurgeConfig implements Serializable {

    /** 固定 1（单行表）*/
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 总开关（可热切换）*/
    private Integer enabled;

    /** 扫描间隔（分钟）*/
    private Integer scanIntervalMinutes;

    /** 计价币（默认 USDT）*/
    private String quoteCurrency;

    /** 主判据：暴增倍数阈值 */
    private BigDecimal surgeRatioThreshold;

    /** 辅判据：1H 价格变化%绝对值下限 */
    @TableField("min_price_change_1h_pct")
    private BigDecimal minPriceChange1hPct;

    /** baseline 窗口（1H 根数）*/
    private Integer baselineHours;

    /** baseline 中位数需 ≥ 该 USDT 阈值 */
    private BigDecimal minBaselineMedianUsdt;

    /** 24h 成交额下限 */
    @TableField("min_24h_quote_volume_usdt")
    private BigDecimal min24hQuoteVolumeUsdt;

    /** 24h 成交额上限 */
    @TableField("max_24h_quote_volume_usdt")
    private BigDecimal max24hQuoteVolumeUsdt;

    /** 预筛：24h 价格波动|阈值|*/
    @TableField("prefilter_min_abs_24h_price_change_pct")
    private BigDecimal prefilterMinAbs24hPriceChangePct;

    /** 新上币过滤天数 */
    private Integer excludeDaysSinceListing;

    /** 冷却期（小时）*/
    private Integer cooldownHours;

    /** 关心方向：UP / DOWN / BOTH */
    private String alertDirections;

    /**
     * 是否用「未收盘的当前 1H bar」判定（1=实时报警，0=只判已收盘 bar）。
     * 打开后，只要当前小时累计成交额 ≥ baseline_median × 阈值 就立即触发，
     * 不做时间进度缩放；避免"事后 30-60min 才告警"的滞后。
     */
    private Integer includeUnclosedBar;

    /** 逗号分隔的黑名单 symbol */
    private String symbolBlacklist;

    /** 逗号分隔的白名单 symbol；空/null = 全扫 */
    private String symbolWhitelist;

    /** Telegram 推送总开关 */
    private Integer telegramEnabled;

    /** Telegram Bot Token */
    private String telegramBotToken;

    /** Telegram Chat ID */
    private String telegramChatId;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Long updateBy;
}
