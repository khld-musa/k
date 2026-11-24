package com.example.ussdGateway.api.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * Aggregated USSD transaction report for a date range (optionally scoped to one node),
 * built from the {@code ussd_transactions} table.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportSummaryResponse implements java.io.Serializable {
    private static final long serialVersionUID = 1L;

    private String startDate;
    private String endDate;
    private String nodeIp;

    private long totalTransactions;
    private long successCount;
    private long failedCount;
    private double successRatePercent;
    private Double averageLatencyMs;

    private List<DailyCount> dailyBreakdown;
    private List<NodeCount> nodeBreakdown;
    private List<ErrorCodeCount> topErrorCodes;
    private List<ShortCodeCount> topShortCodes;
    private List<PackageCount> topPackages;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DailyCount implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        private LocalDate day;
        private long total;
        private long success;
        private long failed;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NodeCount implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        private String nodeIp;
        private long total;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ErrorCodeCount implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        private String errorCode;
        private long total;
        /** One representative error message for this code */
        private String sampleMessage;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ShortCodeCount implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        private String shortCode;
        private String productName;
        private long total;
        private long success;
        private long failed;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PackageCount implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        private String productName;
        private String shortCode;
        private long total;
        private long success;
        private long failed;
    }
}
