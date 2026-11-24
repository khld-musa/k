package com.example.ussdGateway.service;

import com.example.ussdGateway.api.dto.response.ServerHealthResponse;
import com.example.ussdGateway.api.dto.response.ServerHealthResponse.*;
import com.example.ussdGateway.entity.UssdServer;
import com.example.ussdGateway.repository.UssdServerRepository;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ServerHealthService {

    private final DataSource dataSource;
    private final UssdServerRepository ussdServerRepository;

    public ServerHealthResponse getHealthStatus() {
        LocalDateTime now = LocalDateTime.now();
        List<HealthAlert> alerts = new ArrayList<>();

        // 1. Uptime
        RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
        long uptimeMs = runtimeBean.getUptime();
        long uptimeSec = TimeUnit.MILLISECONDS.toSeconds(uptimeMs);
        String formattedUptime = formatUptime(uptimeMs);

        // 2. OS & CPU
        OsInfo osInfo = collectOsInfo();

        // 3. Memory
        MemoryInfo memInfo = collectMemoryInfo(alerts);

        // 4. Storage
        List<StoragePartitionInfo> storageList = collectStorageInfo(alerts);

        // 5. Database Connection Pool
        DatabaseHealthInfo dbInfo = collectDatabaseInfo(alerts);

        // 6. JVM Runtime
        JvmInfo jvmInfo = collectJvmInfo();

        // 7. Remote USSD Nodes Reachability
        List<NodeReachabilityInfo> nodeReachabilities = checkNodeReachability(alerts);

        // 8. Overall status evaluation
        String overallStatus = "HEALTHY";
        boolean hasCritical = alerts.stream().anyMatch(a -> "CRITICAL".equalsIgnoreCase(a.getLevel()));
        boolean hasWarning = alerts.stream().anyMatch(a -> "WARNING".equalsIgnoreCase(a.getLevel()));

        if (hasCritical) {
            overallStatus = "CRITICAL";
        } else if (hasWarning) {
            overallStatus = "WARNING";
        }

        return ServerHealthResponse.builder()
                .overallStatus(overallStatus)
                .timestamp(now)
                .uptime(formattedUptime)
                .uptimeSeconds(uptimeSec)
                .os(osInfo)
                .memory(memInfo)
                .storage(storageList)
                .database(dbInfo)
                .jvm(jvmInfo)
                .nodes(nodeReachabilities)
                .alerts(alerts)
                .build();
    }

    private OsInfo collectOsInfo() {
        java.lang.management.OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        String name = osBean.getName();
        String arch = osBean.getArch();
        String version = osBean.getVersion();
        int processors = osBean.getAvailableProcessors();
        double loadAvg = osBean.getSystemLoadAverage();

        Double systemCpu = null;
        Double processCpu = null;

        if (osBean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
            double sysLoad = sunBean.getCpuLoad();
            if (sysLoad >= 0) {
                systemCpu = Math.round(sysLoad * 1000.0) / 10.0;
            }
            double procLoad = sunBean.getProcessCpuLoad();
            if (procLoad >= 0) {
                processCpu = Math.round(procLoad * 1000.0) / 10.0;
            }
        }

        return OsInfo.builder()
                .name(name)
                .arch(arch)
                .version(version)
                .availableProcessors(processors)
                .systemCpuPercent(systemCpu)
                .processCpuPercent(processCpu)
                .systemLoadAverage(loadAvg >= 0 ? Math.round(loadAvg * 100.0) / 100.0 : null)
                .build();
    }

    private MemoryInfo collectMemoryInfo(List<HealthAlert> alerts) {
        long totalPhysical = 0L;
        long freePhysical = 0L;
        long usedPhysical = 0L;
        double physicalUsagePercent = 0.0;

        java.lang.management.OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        if (osBean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
            totalPhysical = sunBean.getTotalMemorySize();
            freePhysical = sunBean.getFreeMemorySize();
            usedPhysical = Math.max(0, totalPhysical - freePhysical);
            if (totalPhysical > 0) {
                physicalUsagePercent = Math.round((usedPhysical * 1000.0) / totalPhysical) / 10.0;
            }
        }

        MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memBean.getHeapMemoryUsage();
        long heapUsed = heap.getUsed();
        long heapMax = heap.getMax() > 0 ? heap.getMax() : heap.getCommitted();
        long heapCommitted = heap.getCommitted();
        double heapUsagePercent = heapMax > 0 ? Math.round((heapUsed * 1000.0) / heapMax) / 10.0 : 0.0;

        MemoryUsage nonHeap = memBean.getNonHeapMemoryUsage();
        long nonHeapUsed = nonHeap.getUsed();

        String status = "HEALTHY";
        if (physicalUsagePercent >= 92.0 || heapUsagePercent >= 92.0) {
            status = "CRITICAL";
            alerts.add(HealthAlert.builder()
                    .level("CRITICAL")
                    .category("RAM")
                    .title("Critical Memory Saturation")
                    .message(String.format("Host RAM is at %.1f%% and JVM Heap is at %.1f%%.", physicalUsagePercent, heapUsagePercent))
                    .recommendation("Review active background threads or allocate additional RAM to prevent OutOfMemory errors and service interruption.")
                    .build());
        } else if (physicalUsagePercent >= 82.0 || heapUsagePercent >= 82.0) {
            status = "WARNING";
            alerts.add(HealthAlert.builder()
                    .level("WARNING")
                    .category("RAM")
                    .title("Elevated Memory Usage")
                    .message(String.format("Host RAM is at %.1f%% and JVM Heap is at %.1f%%.", physicalUsagePercent, heapUsagePercent))
                    .recommendation("Monitor memory consumption and check for large batch exports or high transaction spikes.")
                    .build());
        }

        return MemoryInfo.builder()
                .totalPhysicalBytes(totalPhysical)
                .usedPhysicalBytes(usedPhysical)
                .freePhysicalBytes(freePhysical)
                .physicalUsagePercent(physicalUsagePercent)
                .jvmHeapUsedBytes(heapUsed)
                .jvmHeapMaxBytes(heapMax)
                .jvmHeapCommittedBytes(heapCommitted)
                .jvmHeapUsagePercent(heapUsagePercent)
                .jvmNonHeapUsedBytes(nonHeapUsed)
                .status(status)
                .build();
    }

    private List<StoragePartitionInfo> collectStorageInfo(List<HealthAlert> alerts) {
        List<StoragePartitionInfo> partitions = new ArrayList<>();
        Set<String> processedCanonicalPaths = new HashSet<>();

        // Candidate paths to check
        List<Map.Entry<String, String>> candidatePaths = new ArrayList<>();
        candidatePaths.add(Map.entry("/", "Root Partition (/)"));

        String userDir = System.getProperty("user.dir", ".");
        candidatePaths.add(Map.entry(userDir, "Application Workspace"));

        // Add local log paths from configured USSD servers
        try {
            List<UssdServer> servers = ussdServerRepository.findAll();
            for (UssdServer server : servers) {
                if (server.getLocalLogPath() != null && !server.getLocalLogPath().isBlank()) {
                    candidatePaths.add(Map.entry(server.getLocalLogPath(), "USSD Log Store (" + server.getName() + ")"));
                }
            }
        } catch (Exception e) {
            log.warn("Could not retrieve USSD server log paths for health check: {}", e.getMessage());
        }

        // Also add system root partitions
        File[] roots = File.listRoots();
        if (roots != null) {
            for (File root : roots) {
                candidatePaths.add(Map.entry(root.getAbsolutePath(), "Filesystem (" + root.getAbsolutePath() + ")"));
            }
        }

        for (Map.Entry<String, String> entry : candidatePaths) {
            String path = entry.getKey();
            String label = entry.getValue();

            File targetFile = findExistingFileOrParent(path);
            if (targetFile == null) continue;

            try {
                String canonical = targetFile.getCanonicalPath();
                if (processedCanonicalPaths.contains(canonical)) {
                    continue;
                }
                processedCanonicalPaths.add(canonical);

                long total = targetFile.getTotalSpace();
                long usable = targetFile.getUsableSpace();
                long free = targetFile.getFreeSpace();
                if (total <= 0) continue;

                long used = Math.max(0, total - usable);
                double usagePercent = Math.round((used * 1000.0) / total) / 10.0;

                String status = "HEALTHY";
                if (usagePercent >= 90.0) {
                    status = "CRITICAL";
                    alerts.add(HealthAlert.builder()
                            .level("CRITICAL")
                            .category("STORAGE")
                            .title("Critical Storage Capacity: " + label)
                            .message(String.format("Storage on %s has reached %.1f%% used (%s remaining).",
                                    path, usagePercent, formatBytes(usable)))
                            .recommendation("Immediate action required: delete or archive old raw logs to prevent log ingestion lock-up and service disruption.")
                            .build());
                } else if (usagePercent >= 80.0) {
                    status = "WARNING";
                    alerts.add(HealthAlert.builder()
                            .level("WARNING")
                            .category("STORAGE")
                            .title("Storage Warning: " + label)
                            .message(String.format("Storage on %s is at %.1f%% used (%s remaining).",
                                    path, usagePercent, formatBytes(usable)))
                            .recommendation("Plan log rotation and cleanup historical archives to keep free capacity above 20%.")
                            .build());
                }

                partitions.add(StoragePartitionInfo.builder()
                        .path(path)
                        .name(label)
                        .totalBytes(total)
                        .freeBytes(free)
                        .usableBytes(usable)
                        .usedBytes(used)
                        .usagePercent(usagePercent)
                        .status(status)
                        .build());

            } catch (Exception ex) {
                log.debug("Failed to read partition stats for {}: {}", path, ex.getMessage());
            }
        }

        // Sort by highest usage first
        partitions.sort((a, b) -> Double.compare(b.getUsagePercent(), a.getUsagePercent()));
        return partitions;
    }

    private DatabaseHealthInfo collectDatabaseInfo(List<HealthAlert> alerts) {
        String status = "CONNECTED";
        String productName = "PostgreSQL";
        int active = 0;
        int idle = 0;
        int total = 0;
        int max = 20;
        Long latencyMs = null;

        long start = System.currentTimeMillis();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("SELECT 1");
            latencyMs = System.currentTimeMillis() - start;

            DatabaseMetaData meta = conn.getMetaData();
            productName = meta.getDatabaseProductName() + " " + meta.getDatabaseProductVersion();
        } catch (Exception e) {
            status = "DOWN";
            alerts.add(HealthAlert.builder()
                    .level("CRITICAL")
                    .category("DATABASE")
                    .title("Database Unreachable")
                    .message("Failed to acquire connection or execute ping: " + e.getMessage())
                    .recommendation("Verify PostgreSQL service health, credentials, and connection limits.")
                    .build());
        }

        if (dataSource instanceof HikariDataSource hikari) {
            max = hikari.getMaximumPoolSize();
            HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
            if (pool != null) {
                active = pool.getActiveConnections();
                idle = pool.getIdleConnections();
                total = pool.getTotalConnections();
            }
        }

        return DatabaseHealthInfo.builder()
                .status(status)
                .databaseProductName(productName)
                .activeConnections(active)
                .idleConnections(idle)
                .totalConnections(total)
                .maxConnections(max)
                .pingLatencyMs(latencyMs)
                .build();
    }

    private JvmInfo collectJvmInfo() {
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        return JvmInfo.builder()
                .javaVersion(System.getProperty("java.version"))
                .jvmVendor(System.getProperty("java.vendor"))
                .threadCount(threadBean.getThreadCount())
                .peakThreadCount(threadBean.getPeakThreadCount())
                .daemonThreadCount(threadBean.getDaemonThreadCount())
                .build();
    }

    private List<NodeReachabilityInfo> checkNodeReachability(List<HealthAlert> alerts) {
        List<NodeReachabilityInfo> list = new ArrayList<>();
        List<UssdServer> servers;
        try {
            servers = ussdServerRepository.findAll();
        } catch (Exception e) {
            log.warn("Could not query ussd servers: {}", e.getMessage());
            return list;
        }

        for (UssdServer server : servers) {
            if (!server.isActive()) {
                list.add(NodeReachabilityInfo.builder()
                        .id(server.getId())
                        .name(server.getName())
                        .ipAddress(server.getIpAddress())
                        .active(false)
                        .status("INACTIVE")
                        .build());
                continue;
            }

            long start = System.currentTimeMillis();
            boolean reachable = false;
            String errorMsg = null;
            long latency = 0;

            try (Socket socket = new Socket()) {
                // Check SSH port 22 with 1200ms timeout
                socket.connect(new InetSocketAddress(server.getIpAddress(), 22), 1200);
                latency = System.currentTimeMillis() - start;
                reachable = true;
            } catch (Exception e) {
                latency = System.currentTimeMillis() - start;
                errorMsg = e.getClass().getSimpleName() + ": " + (e.getMessage() != null ? e.getMessage() : "Timed out");
            }

            if (!reachable) {
                alerts.add(HealthAlert.builder()
                        .level("WARNING")
                        .category("NODE")
                        .title("USSD Node Unreachable: " + server.getName())
                        .message("Node " + server.getName() + " (" + server.getIpAddress() + ":22) failed reachability check: " + errorMsg)
                        .recommendation("Verify the USSD server network link, SSH daemon status, and firewall rules.")
                        .build());
            }

            list.add(NodeReachabilityInfo.builder()
                    .id(server.getId())
                    .name(server.getName())
                    .ipAddress(server.getIpAddress())
                    .active(true)
                    .status(reachable ? "REACHABLE" : "UNREACHABLE")
                    .latencyMs(reachable ? latency : null)
                    .error(errorMsg)
                    .build());
        }

        return list;
    }

    private File findExistingFileOrParent(String path) {
        if (path == null || path.isBlank()) return null;
        File file = new File(path);
        while (file != null && !file.exists()) {
            file = file.getParentFile();
        }
        return file;
    }

    private String formatUptime(long uptimeMs) {
        long seconds = TimeUnit.MILLISECONDS.toSeconds(uptimeMs) % 60;
        long minutes = TimeUnit.MILLISECONDS.toMinutes(uptimeMs) % 60;
        long hours = TimeUnit.MILLISECONDS.toHours(uptimeMs) % 24;
        long days = TimeUnit.MILLISECONDS.toDays(uptimeMs);

        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0 || days > 0) sb.append(hours).append("h ");
        sb.append(minutes).append("m ");
        sb.append(seconds).append("s");
        return sb.toString().trim();
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char unit = "KMGTPE".charAt(exp - 1);
        return String.format(Locale.US, "%.1f %cB", bytes / Math.pow(1024, exp), unit);
    }
}
