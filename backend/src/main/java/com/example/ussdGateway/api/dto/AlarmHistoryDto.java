package com.example.ussdGateway.api.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class AlarmHistoryDto {
    private Long id;
    private Long alarmConfigId;
    private String username;
    private String clientId;
    private BigDecimal currentBalance;
    private BigDecimal threshold;
    private LocalDateTime triggeredAt;
    private String smsMessage;
    private String emailMessage;
}
