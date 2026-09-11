package com.vertex.service.mm.market;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** 一档买/卖盘 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class OrderBookLevel {
    private BigDecimal price;
    private BigDecimal qty;
}
