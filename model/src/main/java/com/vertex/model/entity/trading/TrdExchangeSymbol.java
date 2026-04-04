package com.vertex.model.entity.trading;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.vertex.common.core.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 交易所币对映射表
 * <p>
 * 唯一键：(exchange, symbol, market_type)
 * <p>
 * 用途：
 * <ul>
 *   <li>正向查询：canonical symbol (ETHUSDT) + exchange + marketType → exchange_symbol（用于下单/订阅）</li>
 *   <li>反向查询：exchange + exchange_symbol → canonical symbol（WebSocket 消息归一化）</li>
 * </ul>
 * <p>
 * 当前只有 Binance 接入，exchange_symbol == symbol（ETHUSDT = ETHUSDT）。
 * 未来接入 OKX 时，exchange_symbol = ETH-USDT-SWAP 等。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("trd_exchange_symbol")
public class TrdExchangeSymbol extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 平台通用标识，关联 trd_symbol.symbol，如 ETHUSDT */
    private String symbol;

    /** 交易所代码，如 binance */
    private String exchange;

    /** 市场类型：SPOT / USDM / COINM */
    private String marketType;

    /** 交易所实际标识（如 Binance=ETHUSDT，OKX=ETH-USDT-SWAP） */
    private String exchangeSymbol;

    /** 状态：TRADING / BREAK 等 */
    private String status;

    /** 最小名义价值 */
    private BigDecimal minNotional;

    /** 数量步长（stepSize） */
    private BigDecimal lotSize;

    /** 价格步长（tickSize） */
    private BigDecimal tickSize;

    /** 最后从交易所同步时间 */
    private LocalDateTime syncTime;
}
