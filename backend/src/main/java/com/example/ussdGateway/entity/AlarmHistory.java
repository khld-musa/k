package com.example.ussdGateway.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "alarm_history")
@Data
public class AlarmHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "alarm_config_id", nullable = false)
    private Long alarmConfigId;

    @Column(name = "username")
    private String username;

    @Column(name = "client_id", nullable = false)
    private String clientId;

    @Column(name = "current_balance", nullable = false)
    private BigDecimal currentBalance;

    @Column(name = "threshold", nullable = false)
    private BigDecimal threshold;

    @Column(name = "triggered_at", nullable = false)
    private LocalDateTime triggeredAt;

    @Column(name = "sms_message", columnDefinition = "TEXT")
    private String smsMessage;

    @Column(name = "email_message", columnDefinition = "TEXT")
    private String emailMessage;
}
