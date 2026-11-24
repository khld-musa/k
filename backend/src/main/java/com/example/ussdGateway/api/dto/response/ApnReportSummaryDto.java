package com.example.ussdGateway.api.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApnReportSummaryDto {

    private String startDate;
    private String endDate;
    private long totalRequests;
    private long successCount;
    private long throttledCount;
    private long failedCount;
    private double successRatePercent;
    private Double averageLatencyMs;
    private List<DailyCount> dailyBreakdown;
    private List<StatusCount> statusBreakdown;
    private String circuitBreakerState;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DailyCount {
        private LocalDate day;
        private long total;
        private long success;
        private long throttled;
        private long failed;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class StatusCount {
        private String status;
        private long total;
    }
}
