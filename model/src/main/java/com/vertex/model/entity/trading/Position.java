package com.vertex.model.entity.trading;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.vertex.common.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 持仓实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("trd_position")
public class Position extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 关联策略ID */
    private Long strategyId;

    /** 关联账户ID */
    private Long accountId;

    /** 交易所 */
    private String exchange;

    /** 交易对 */
    private String symbol;

    /** 持仓方向 LONG/SHORT */
    private PositionSide side;

    /** 持仓数量 */
    private BigDecimal quantity;

    /** 开仓均价 */
    private BigDecimal entryPrice;

    /** 当前价格 */
    private BigDecimal currentPrice;

    /** 未实现盈亏 */
    private BigDecimal unrealizedPnl;

    /** 已实现盈亏 */
    private BigDecimal realizedPnl;

    /** 止损价 */
    private BigDecimal stopLoss;

    /** 止盈价 */
    private BigDecimal takeProfit;

    /** 平仓价格 */
    private BigDecimal closePrice;

    /** 平仓时间 */
    private LocalDateTime closedAt;

    /** 持仓状态 OPEN/CLOSED */
    private PositionStatus status;

    /** 交易模式 LIVE/PAPER */
    private ExecutionMode tradeMode;

    // ─── 合约专用字段 ─────────────────────────────────

    /** 市场类型 SPOT/USDM/COINM */
    private MarketType marketType;

    /** 杠杆倍数 */
    private Integer leverage;

    /** 保证金模式 ISOLATED/CROSS */
    private MarginType marginType;

    /** 强平价格（合约专用） */
    private BigDecimal liquidationPrice;

    /** 最新资金费率（合约专用） */
    private BigDecimal fundingRate;

    // ─── 出场条件追踪字段 ───────────────────────────────────────────

    /** 开仓后经历的K线根数（时间止损用，每根K线+1，null/0=未统计） */
    private Integer openBarCount;

    // ─── 移动止损 / 峰值回撤止损追踪字段 ────────────────────────────

    /** 当前止损阶段：INITIAL / BREAKEVEN / TRAILING */
    @TableField("`stop_loss_stage`")
    private StopLossStage stopLossStage;

    /**
     * LONG 持仓追踪最高价。
     * 用途1：移动ATR止损 TRAILING 阶段，追踪最高价以上移止损价。
     * 用途2：峰值回撤止损（trailingDropPct），从最高价回撤超过阈值时平仓。
     * SHORT 持仓使用 lowestPrice，此字段对空头无实际意义（开仓时会初始化为入场价）。
     */
    private BigDecimal highestPrice;

    /**
     * SHORT 持仓追踪最低价。
     * 用途1：移动ATR止损 TRAILING 阶段，追踪最低价以下移止损价。
     * 用途2：峰值回撤止损（trailingDropPct），从最低价反弹超过阈值时平仓。
     * LONG 持仓使用 highestPrice，此字段对多头无实际意义（开仓时会初始化为入场价）。
     */
    private BigDecimal lowestPrice;

    /**
     * SuperTrend 动态止损价。
     * <p>
     * 由 StrategyEngineService 在每次 K 线收盘评估时写入：
     * <ul>
     *   <li>LONG：superTrend × (1 - offsetPct%)，即支撑位下方</li>
     *   <li>SHORT：superTrend × (1 + offsetPct%)，即阻力位上方</li>
     * </ul>
     * 趋势反转时冻结（不更新），等待其他止损机制或反向信号平仓。
     * null 或 &lt;= 0 表示未启用，StopLossTakeProfitTask 将跳过此止损检查。
     * </p>
     */
    private BigDecimal superTrendStopLoss;
}
