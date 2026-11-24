package com.example.ussdGateway.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;

@Entity
@Table(name = "alarm_configs")
@Data
public class AlarmConfig {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username")
    private String username;

    @Column(name = "client_id", nullable = false)
    private String clientId;

    @Convert(converter = com.example.ussdGateway.util.AttributeEncryptor.class)
    @Column(name = "api_key", nullable = false)
    private String apiKey;

    @Column(name = "minimum_threshold", nullable = false)
    private BigDecimal minimumThreshold;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    /** Per-alarm check interval in minutes. Default 30. */
    @Column(name = "check_interval_minutes", nullable = false)
    private Integer checkIntervalMinutes = 30;

    /** Per-alarm alert cooldown in minutes. Default 360 (6 hours). */
    @Column(name = "cooldown_minutes", nullable = false)
    private Integer cooldownMinutes = 360;

    /** Timestamp of the last time this alarm was actually checked by the scheduler. */
    @Column(name = "last_checked_at")
    private LocalDateTime lastCheckedAt;

    @Column(name = "last_alert_sent_at")
    private LocalDateTime lastAlertSentAt;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "alarm_group_subscriptions",
        joinColumns = @JoinColumn(name = "alarm_id"),
        inverseJoinColumns = @JoinColumn(name = "group_id")
    )
    private Set<ContactGroup> subscribedGroups;
}
