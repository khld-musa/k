package com.example.ussdGateway.api.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServerHealthResponse {

    private String overallStatus; // "HEALTHY", "WARNING", "CRITICAL"
    private LocalDateTime timestamp;
    private String uptime;
    private long uptimeSeconds;

    private OsInfo os;
    private MemoryInfo memory;
    @Builder.Default
    private List<StoragePartitionInfo> storage = new ArrayList<>();
    private DatabaseHealthInfo database;
    private JvmInfo jvm;
    @Builder.Default
    private List<NodeReachabilityInfo> nodes = new ArrayList<>();
    @Builder.Default
    private List<HealthAlert> alerts = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OsInfo {
        private String name;
        private String arch;
        private String version;
        private int availableProcessors;
        private Double systemCpuPercent;
        private Double processCpuPercent;
        private Double systemLoadAverage;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MemoryInfo {
        private long totalPhysicalBytes;
        private long usedPhysicalBytes;
        private long freePhysicalBytes;
        private double physicalUsagePercent;

        private long jvmHeapUsedBytes;
        private long jvmHeapMaxBytes;
        private long jvmHeapCommittedBytes;
        private double jvmHeapUsagePercent;

        private long jvmNonHeapUsedBytes;
        private String status; // "HEALTHY", "WARNING", "CRITICAL"
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StoragePartitionInfo {
        private String path;
        private String name;
        private long totalBytes;
        private long freeBytes;
        private long usableBytes;
        private long usedBytes;
        private double usagePercent;
        private String status; // "HEALTHY", "WARNING", "CRITICAL"
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DatabaseHealthInfo {
        private String status; // "CONNECTED", "DEGRADED", "DOWN"
        private String databaseProductName;
        private int activeConnections;
        private int idleConnections;
        private int totalConnections;
        private int maxConnections;
        private Long pingLatencyMs;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class JvmInfo {
        private String javaVersion;
        private String jvmVendor;
        private int threadCount;
        private int peakThreadCount;
        private int daemonThreadCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NodeReachabilityInfo {
        private Long id;
        private String name;
        private String ipAddress;
        private boolean active;
        private String status; // "REACHABLE", "UNREACHABLE", "INACTIVE"
        private Long latencyMs;
        private String error;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HealthAlert {
        private String level; // "INFO", "WARNING", "CRITICAL"
        private String category; // "STORAGE", "RAM", "CPU", "DATABASE", "NODE"
        private String title;
        private String message;
        private String recommendation;
    }
}
