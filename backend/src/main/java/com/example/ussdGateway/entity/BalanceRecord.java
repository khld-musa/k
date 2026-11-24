package com.example.ussdGateway.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Stores the current balance status for each alarm configuration.
 * Unlike AlarmHistory which records every threshold breach event, this table maintains
 * one record per alarm config that is updated on each balance check to save database space.
 */
@Entity
@Table(name = "balance_records", 
    indexes = {
        @Index(name = "idx_balance_client_id", columnList = "client_id"),
        @Index(name = "idx_balance_checked_at", columnList = "checked_at"),
        @Index(name = "idx_balance_alarm_config_id", columnList = "alarm_config_id")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_balance_alarm_config_id", columnNames = "alarm_config_id")
    }
)
@Data
public class BalanceRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "alarm_config_id", nullable = false)
    private Long alarmConfigId;

    @Column(name = "username")
    private String username;

    @Column(name = "client_id", nullable = false)
    private String clientId;

    @Column(name = "balance", nullable = false, precision = 15, scale = 2)
    private BigDecimal balance;

    @Column(name = "threshold", nullable = false, precision = 15, scale = 2)
    private BigDecimal threshold;

    @Column(name = "checked_at", nullable = false)
    private LocalDateTime checkedAt;

    @Column(name = "is_below_threshold", nullable = false)
    private Boolean isBelowThreshold;

    @Column(name = "alert_triggered", nullable = false)
    private Boolean alertTriggered = false;
}

