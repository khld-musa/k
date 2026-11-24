package com.example.ussdGateway.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "report_schedules")
@Data
public class ReportSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /** DAILY, WEEKLY, MONTHLY */
    @Column(nullable = false, length = 20)
    private String frequency;

    /** e.g. MONDAY — only used when frequency=WEEKLY */
    @Column(name = "day_of_week", length = 10)
    private String dayOfWeek;

    /** 1-28 — only used when frequency=MONTHLY */
    @Column(name = "day_of_month")
    private Integer dayOfMonth;

    /** HH:mm format in server-local time, e.g. "08:00" */
    @Column(name = "time_of_day", nullable = false, length = 5)
    private String timeOfDay;

    /** Number of days back the report covers (e.g. 1 for daily, 7 for weekly) */
    @Column(name = "range_days", nullable = false)
    private Integer rangeDays = 1;

    /** Optional: filter to a specific USSD node. null = ALL nodes */
    @Column(name = "node_ip", length = 45)
    private String nodeIp;

    /** Comma-separated contact group IDs, e.g. "1,3,5" */
    @Column(name = "contact_group_ids", nullable = false, columnDefinition = "TEXT")
    private String contactGroupIds;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    @Column(name = "next_run_at", nullable = false)
    private LocalDateTime nextRunAt;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

