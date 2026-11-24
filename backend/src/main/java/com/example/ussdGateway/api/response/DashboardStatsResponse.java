package com.example.ussdGateway.api.response;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardStatsResponse {
    
    private LocalDate dataDate;

    // ── Active Feature Stats ──
    private long totalNodes;
    private long searchesToday;
    private long tracesToday;
    private long totalAlarmConfigs;

    // ── Charts ──
    private List<DailyActivity> activityChart;

    // ── Feed ──
    private List<AuditLogDto> recentActivity;

    // ── Balance status (admin only) ──
    private List<BalanceStatus> balanceStatuses;

    // ── Recent alarms ──
    private List<RecentAlarm> recentAlarms;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DailyActivity {
        private String date; // "Mon", "Tue" etc.
        private long searches;
        private long traces;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AuditLogDto {
        private String action;
        private String username;
        private String details;
        private String timeAgo;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BalanceStatus {
        private String accountName;
        private String clientId;
        private BigDecimal currentBalance;
        private BigDecimal threshold;
        private boolean isLow;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecentAlarm {
        private String accountName;
        private BigDecimal balance;
        private BigDecimal threshold;
        private String timeAgo;
    }
}
