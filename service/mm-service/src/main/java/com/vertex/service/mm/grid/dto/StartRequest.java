package com.vertex.service.mm.grid.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class StartRequest {
    /** 可选，为空则用当前 mid price */
    private BigDecimal initialCenterPrice;
}
