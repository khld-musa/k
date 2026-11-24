package com.example.ussdGateway.api.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class ReportScheduleDto {
    private Long id;
    private String name;
    private String frequency;       // DAILY, WEEKLY, MONTHLY
    private String dayOfWeek;       // MONDAY..SUNDAY (for WEEKLY)
    private Integer dayOfMonth;     // 1-28 (for MONTHLY)
    private String timeOfDay;       // "HH:mm"
    private Integer rangeDays;
    private String nodeIp;          // null or IP for node filter
    private List<Long> contactGroupIds;
    private Boolean isActive;
    private LocalDateTime lastRunAt;
    private LocalDateTime nextRunAt;
    private String createdBy;
    private LocalDateTime createdAt;
}

