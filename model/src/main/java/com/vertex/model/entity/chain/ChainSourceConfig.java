package com.vertex.model.entity.chain;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.vertex.common.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 链上数据源运行时配置实体
 * <p>
 * 对应数据库表 chn_source_config，用于在 UI 中动态调整
 * bnb_alpha / bnb_trending 筛选参数，无需重启服务。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("chn_source_config")
public class ChainSourceConfig extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 数据源标识：bnb_alpha | bnb_trending */
    private String sourceId;

    /** 数据源显示名称 */
    private String sourceName;

    /** 是否启用：0-禁用 1-启用 */
    private Integer enabled;

    /** 最低市值 USD */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Double minMarketCapUsd;

    /** 最高市值 USD（null=不限，仅 bnb_trending 使用） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Double maxMarketCapUsd;

    /** 最低流动性 USD */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Double minLiquidityUsd;

    /** 最低成交量/流动性换手率（仅 bnb_trending 使用） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Double minVolumeLiquidityRatio;

    /** 1h 价格最低涨幅（仅 bnb_trending 使用） */
    // MyBatis Plus 自动转换 minPriceChange1hPct -> min_price_change1h_pct，与列名不符，需显式指定
    @TableField(value = "min_price_change_1h_pct", updateStrategy = FieldStrategy.ALWAYS)
    private Double minPriceChange1hPct;

    /** 每次 API 拉取数量（仅 bnb_alpha 使用） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer pageSize;

    /** 每次扫描最多处理代币数（仅 bnb_trending 使用） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer scanLimit;
}
