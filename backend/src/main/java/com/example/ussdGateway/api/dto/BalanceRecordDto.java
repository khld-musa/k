package com.example.ussdGateway.api.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * DTO for BalanceRecord entity - represents a single balance API query result.
 */
@Data
public class BalanceRecordDto {
    
    private Long id;
    private Long alarmConfigId;
    private String username;
    private String clientId;
    private BigDecimal balance;
    private BigDecimal threshold;
    private LocalDateTime checkedAt;
    private Boolean isBelowThreshold;
    private Boolean alertTriggered;
}

