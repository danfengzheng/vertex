package com.vertex.model.entity.chain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.vertex.common.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 链上新发现代币实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("chn_token")
public class ChainToken extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 链标识：BNB / SOLANA */
    private String chain;

    /** 合约地址（BSC）或 Mint 地址（Solana） */
    private String contractAddress;

    /** 代币符号 */
    private String symbol;

    /** 代币名称 */
    private String name;

    /** 精度 */
    private Integer decimals;

    /** 总供应量（字符串，避免大数精度丢失） */
    private String totalSupply;

    /** 部署者/创建者地址 */
    private String deployerAddress;

    /** 部署区块号 */
    private Long deployBlock;

    /** 部署时间戳（毫秒） */
    private Long deployTime;

    /** 综合评分 0-100 */
    private Integer score;

    /** 链上维度分 0-30 */
    private Integer scoreOnchain;

    /** 市场维度分 0-40 */
    private Integer scoreMarket;

    /** 代币经济维度分 0-20 */
    private Integer scoreTokenomics;

    /** 新颖度维度分 0-10 */
    private Integer scoreNovelty;

    /** 状态：PENDING / SCORED / ALERTED / IGNORED */
    private String status;

    /** 是否已告警：0-否 1-是 */
    private Integer alerted;

    /** 触发告警的分值 */
    private Integer alertScore;

    /** DEX 交易对地址 */
    private String pairAddress;

    /** 计价代币（WBNB / USDT / SOL） */
    private String quoteToken;

    /** 原始元数据 JSON */
    private String rawMeta;
}
