package com.vertex.model.dto.chain;

import com.vertex.model.query.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 链上新币分页查询参数
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ChainTokenQueryDTO extends PageQuery {

    /** 链标识：BNB / SOLANA */
    private String chain;

    /** 代币符号（模糊查询） */
    private String symbol;

    /** 最低综合评分 */
    private Integer minScore;

    /** 状态：PENDING / SCORED / ALERTED / IGNORED */
    private String status;

    /** 部署时间起（毫秒时间戳） */
    private Long deployTimeFrom;

    /** 部署时间止（毫秒时间戳） */
    private Long deployTimeTo;

    /** 数据来源过滤：bnb_primary / bnb_alpha / bnb_trending 等 */
    private String dataSource;
}
