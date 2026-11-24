package com.example.ussdGateway.service;

import com.example.ussdGateway.api.response.DashboardStatsResponse;
import com.example.ussdGateway.api.response.DashboardSummaryResponse;
import com.example.ussdGateway.entity.AlarmConfig;
import com.example.ussdGateway.entity.AlarmHistory;
import com.example.ussdGateway.repository.AlarmConfigRepository;
import com.example.ussdGateway.repository.AlarmHistoryRepository;
import com.example.ussdGateway.repository.AuditLogRepository;
import com.example.ussdGateway.repository.UssdServerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {

    private static final List<String> ACTIVITY_ACTIONS = List.of("SEARCH_LOGS", "START_LIVE_TRACE");

    private final AuditLogRepository auditLogRepository;
    private final UssdServerRepository ussdServerRepository;
    private final AlarmConfigRepository alarmConfigRepository;
    private final AlarmHistoryRepository alarmHistoryRepository;

    // ─────────────────────────────────────────────────────────────
    //  PUBLIC: Lightweight summary for ALL authenticated users
    // ─────────────────────────────────────────────────────────────
    public DashboardSummaryResponse getDashboardSummary(String username) {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();

        // [PERF FIX-5] Use SQL COUNT instead of fetching 1000 rows and filtering in Java
        long activeNodes   = ussdServerRepository.findByActiveTrue().size();
        long searchesToday = auditLogRepository.countByActionSince("SEARCH_LOGS", todayStart);
        long tracesToday   = auditLogRepository.countByActionSince("START_LIVE_TRACE", todayStart);

        return DashboardSummaryResponse.builder()
                .dataDate(LocalDate.now())
                .totalNodes(activeNodes)
                .searchesToday(searchesToday)
                .tracesToday(tracesToday)
                .build();
    }

    // ─────────────────────────────────────────────────────────────
    //  PUBLIC: Full admin dashboard stats
    // ─────────────────────────────────────────────────────────────
    public DashboardStatsResponse getDashboardStats() {
        LocalDate today      = LocalDate.now();
        LocalDateTime todayStart = today.atStartOfDay();

        // 1. Active nodes
        long activeNodes = ussdServerRepository.findByActiveTrue().size();

        // 2. [PERF FIX-5] Kraken usage — SQL COUNT per action, not Java-side filtering of 1000 rows
        long searchesToday = auditLogRepository.countByActionSince("SEARCH_LOGS", todayStart);
        long tracesToday   = auditLogRepository.countByActionSince("START_LIVE_TRACE", todayStart);

        // 3. Active alarm configs
        long totalAlarmConfigs = alarmConfigRepository.findByIsActiveTrue().size();

        // 4. [PERF FIX-5] Activity chart — one SQL COUNT per (action × day) instead of iterating
        //    the same in-memory list 14 times (7 days × 2 actions)
        DateTimeFormatter dayFormatter = DateTimeFormatter.ofPattern("EEE");
        List<DashboardStatsResponse.DailyActivity> activityChart = new ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            LocalDate d        = today.minusDays(i);
            LocalDateTime dayStart = d.atTime(LocalTime.MIN);
            LocalDateTime dayEnd   = d.atTime(LocalTime.MAX);
            long searches = auditLogRepository.countByActionBetween("SEARCH_LOGS", dayStart, dayEnd);
            long traces   = auditLogRepository.countByActionBetween("START_LIVE_TRACE", dayStart, dayEnd);
            activityChart.add(new DashboardStatsResponse.DailyActivity(d.format(dayFormatter), searches, traces));
        }

        // 5. [PERF FIX-5] Recent activity feed — SQL LIMIT instead of streaming 1000 rows in Java
        List<DashboardStatsResponse.AuditLogDto> feed =
                auditLogRepository.findRecentByActions(ACTIVITY_ACTIONS, PageRequest.of(0, 5))
                        .getContent().stream()
                        .map(l -> new DashboardStatsResponse.AuditLogDto(
                                l.getAction(), l.getUsername(), l.getDetails(), formatTimeAgo(l.getTimestamp())))
                        .collect(Collectors.toList());

        // 6. Balance statuses
        List<DashboardStatsResponse.BalanceStatus> balanceStatuses = buildBalanceStatuses();

        // 7. Recent alarms
        List<DashboardStatsResponse.RecentAlarm> recentAlarms = buildRecentAlarms();

        return DashboardStatsResponse.builder()
                .dataDate(today)
                .totalNodes(activeNodes)
                .searchesToday(searchesToday)
                .tracesToday(tracesToday)
                .totalAlarmConfigs(totalAlarmConfigs)
                .activityChart(activityChart)
                .recentActivity(feed)
                .balanceStatuses(balanceStatuses)
                .recentAlarms(recentAlarms)
                .build();
    }

    // ─────────────────────────────────────────────────────────────
    //  PUBLIC: User-specific activity feed
    // ─────────────────────────────────────────────────────────────
    public List<DashboardStatsResponse.AuditLogDto> getUserActivity(String username) {
        return auditLogRepository.findByUsernameAndActionInOrderByTimestampDesc(username, ACTIVITY_ACTIONS, PageRequest.of(0, 5))
                .getContent().stream()
                .map(l -> new DashboardStatsResponse.AuditLogDto(
                        l.getAction(), l.getUsername(), l.getDetails(), formatTimeAgo(l.getTimestamp())))
                .collect(Collectors.toList());
    }

    // ─── Private helpers ─────────────────────────────────────────

    private List<DashboardStatsResponse.BalanceStatus> buildBalanceStatuses() {
        List<AlarmConfig> configs = alarmConfigRepository.findByIsActiveTrue();

        // [PERF FIX-5] Use DISTINCT ON query to get latest history per config in one SQL round-trip
        // instead of loading the entire alarm_history table into memory and grouping in Java.
        List<AlarmHistory> latestHistory = alarmHistoryRepository.findLatestPerConfig();
        Map<Long, AlarmHistory> latestByConfig = new HashMap<>();
        for (AlarmHistory h : latestHistory) {
            latestByConfig.put(h.getAlarmConfigId(), h);
        }

        List<DashboardStatsResponse.BalanceStatus> result = new ArrayList<>();
        for (AlarmConfig config : configs) {
            AlarmHistory latest  = latestByConfig.get(config.getId());
            BigDecimal balance   = latest != null ? latest.getCurrentBalance() : null;
            boolean isLow        = balance != null && balance.compareTo(config.getMinimumThreshold()) <= 0;
            result.add(new DashboardStatsResponse.BalanceStatus(
                    config.getUsername() != null ? config.getUsername() : config.getClientId(),
                    config.getClientId(),
                    balance,
                    config.getMinimumThreshold(),
                    isLow));
        }
        return result;
    }

    private List<DashboardStatsResponse.RecentAlarm> buildRecentAlarms() {
        // [PERF FIX-5] Use findTop3 instead of findAll() + in-Java sort + limit(3).
        // Previously this loaded every alarm history row (potentially thousands) into memory.
        return alarmHistoryRepository.findTop3ByOrderByTriggeredAtDesc().stream()
                .map(h -> new DashboardStatsResponse.RecentAlarm(
                        h.getUsername() != null ? h.getUsername() : h.getClientId(),
                        h.getCurrentBalance(),
                        h.getThreshold(),
                        formatTimeAgo(h.getTriggeredAt())))
                .collect(Collectors.toList());
    }

    private String formatTimeAgo(LocalDateTime timestamp) {
        LocalDateTime now = LocalDateTime.now();
        long minutes = ChronoUnit.MINUTES.between(timestamp, now);
        if (minutes < 1) return "Just now";
        if (minutes < 60) return minutes + "m ago";
        long hours = ChronoUnit.HOURS.between(timestamp, now);
        if (hours < 24) return hours + "h ago";
        long days = ChronoUnit.DAYS.between(timestamp, now);
        return days + "d ago";
    }
}
