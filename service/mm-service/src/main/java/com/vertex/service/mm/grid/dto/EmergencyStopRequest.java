package com.vertex.service.mm.grid.dto;

import lombok.Data;

@Data
public class EmergencyStopRequest {
    /** SOFT_STOP / GRACEFUL_CLOSE / LIMIT_LADDER_CLOSE / MARKET_FALLBACK */
    private String mode;
    private String reason;
}
