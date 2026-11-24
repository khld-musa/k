package com.example.ussdGateway.service;

import com.example.ussdGateway.entity.AlarmConfig;
import com.example.ussdGateway.entity.AlarmHistory;
import com.example.ussdGateway.entity.BalanceRecord;
import com.example.ussdGateway.entity.ContactGroup;
import com.example.ussdGateway.repository.AlarmConfigRepository;
import com.example.ussdGateway.repository.AlarmHistoryRepository;
import com.example.ussdGateway.repository.BalanceRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.web.util.HtmlUtils;

@Service
@RequiredArgsConstructor
@Slf4j
public class BalanceMonitorService {

    private final AlarmConfigRepository alarmConfigRepository;
    private final AlarmHistoryRepository alarmHistoryRepository;
    private final BalanceRecordRepository balanceRecordRepository;
    private final SesmeApiClient sesmeApiClient;
    private final EmailNotificationService emailNotificationService;
    private final SmsNotificationService smsNotificationService;

    // [FIX P0-4] Prevent concurrent execution between scheduled and manual triggers
    private final ReentrantLock monitorLock = new ReentrantLock();
    
    /**
     * Tick every 60 seconds.  For each active alarm we check whether its individual
     * check_interval_minutes has elapsed since its last_checked_at.  This replaces the
     * old global 30-minute fixed-delay schedule with per-alarm granularity.
     *
     * fixedDelay = 60000 ms (1 min), initialDelay = 10000 ms (10 sec)
     */
    @Scheduled(fixedDelay = 60000, initialDelay = 10000)
    public void monitorBalances() {
        monitorBalancesInternal(false);
    }

    private void monitorBalancesInternal(boolean force) {
        // [FIX P0-4] Prevent concurrent execution — if a scheduled run is in progress,
        // a manual trigger will skip rather than queue up and fire duplicate alerts.
        if (!monitorLock.tryLock()) {
            log.info("Balance monitor already running — skipping this trigger (force={}).", force);
            return;
        }
        try {
            doMonitorBalances(force);
        } finally {
            monitorLock.unlock();
        }
    }

    private void doMonitorBalances(boolean force) {

        log.info("Starting balance check tick (force={})...", force);

        // Only load ACTIVE configs — avoids loading/skipping inactive ones on every cycle
        List<AlarmConfig> configs = alarmConfigRepository.findByIsActiveTrue();

        LocalDateTime now = LocalDateTime.now();

        for (AlarmConfig config : configs) {

            try {
                // ── Per-alarm interval gate ──────────────────────────────────
                // Skip this alarm if its individual interval hasn't elapsed yet
                // (manual/force triggers always bypass the gate).
                int intervalMinutes = config.getCheckIntervalMinutes() != null
                        ? config.getCheckIntervalMinutes() : 30;

                if (!force && config.getLastCheckedAt() != null
                        && config.getLastCheckedAt().plusMinutes(intervalMinutes).isAfter(now)) {
                    // Not yet time for this alarm — skip silently
                    continue;
                }

                // 1. Fetch live balance
                BigDecimal currentBalance = sesmeApiClient.getBalance(config.getClientId(), config.getApiKey());

                // Circuit breaker fallback returns null — skip this config, do NOT crash the whole cycle
                if (currentBalance == null) {
                    log.warn("[MONITOR] Could not fetch balance for ClientId '{}' (circuit breaker active or API unreachable). Skipping.", config.getClientId());
                    continue;
                }

                // ── Record the check time immediately after a successful API call ──
                config.setLastCheckedAt(now);

                log.info("[DIAG] Live Balance for ClientId '{}': {} | Threshold: {} | Interval: {} min",
                        config.getClientId(), currentBalance, config.getMinimumThreshold(), intervalMinutes);

                // ── Save balance record to database ──
                saveBalanceRecord(config, currentBalance, now, false);

                // 2. Evaluate threshold breach
                if (currentBalance.compareTo(config.getMinimumThreshold()) < 0) {
                    
                    // 2.1 Per-alarm cooldown check
                    int cooldown = config.getCooldownMinutes() != null
                            ? config.getCooldownMinutes() : 360;
                    if (!force && config.getLastAlertSentAt() != null
                            && config.getLastAlertSentAt().plusMinutes(cooldown).isAfter(LocalDateTime.now())) {
                        log.info("Alert suppressed for {} - cooldown ({} min) active until {}", 
                             config.getUsername(), cooldown, config.getLastAlertSentAt().plusMinutes(cooldown));
                        // Still persist lastCheckedAt so the interval gate works correctly
                        alarmConfigRepository.save(config);
                        continue;
                    }

                    log.warn("ALERT TRIGGERED! Balance ({}) is below threshold ({}) for ClientId: {}", 
                            currentBalance, config.getMinimumThreshold(), config.getClientId());
                            
                    // 3. Dispatch Notifications via Email & SMS to all subscribed groups
                    dispatchAlerts(config, currentBalance);
                    
                    // Update the balance record to mark alert as triggered
                    updateBalanceRecordAlertStatus(config.getId(), now);
                    
                    // 4. Record last alert time
                    config.setLastAlertSentAt(LocalDateTime.now());
                    alarmConfigRepository.save(config);
                } else {
                    log.info("[DIAG] Balance ({}) is ABOVE threshold ({}) for ClientId {}. No alert needed.",
                            currentBalance, config.getMinimumThreshold(), config.getClientId());
                    if (config.getLastAlertSentAt() != null) {
                        log.info("[P1-8] Balance for ClientId {} has recovered — sending recovery notification.", config.getClientId());
                        // [FIX P1-8] Notify subscribed groups that balance has recovered
                        dispatchRecoveryAlerts(config, currentBalance);
                        config.setLastAlertSentAt(null);
                    }
                    // Always persist lastCheckedAt
                    alarmConfigRepository.save(config);
                }
            } catch (Exception e) {
                log.error("Error processing balance monitor for config ID: {}", config.getId(), e);
            }
        }
    }

    private void dispatchAlerts(AlarmConfig config, BigDecimal currentBalance) {
        Set<ContactGroup> groups = config.getSubscribedGroups();
        log.info("[DIAG] dispatchAlerts called for AlarmConfig ID:{}. SubscribedGroups count: {}", 
                config.getId(), (groups == null ? "null" : groups.size()));
        if (groups == null || groups.isEmpty()) {
            log.warn("No contact groups subscribed to alarm config ID: {}. Alerts suppressed.", config.getId());
            return;
        }

        String alertMessageSms = String.format(
            "CRITICAL ALARM: Account balance for username '%s' has fallen to %s. The configured minimum threshold is %s. Please top up immediately.",
            config.getUsername(), currentBalance.toString(), config.getMinimumThreshold().toString()
        );

        String account = config.getUsername();
        String balance = currentBalance.toString();
        String threshold = config.getMinimumThreshold().toString();
        java.util.function.Function<String, String> alertEmail = greetingName -> buildAlertEmailHtml(
            greetingName,
            "Low balance alert",
            "#b42318",
            "The balance for the account below has fallen under its minimum threshold.",
            account, balance, threshold,
            "Please recharge the account as soon as possible to avoid service interruption."
        );

        log.info("[DIAG] Dispatching Email and SMS alerts to {} groups...", groups.size());
        emailNotificationService.sendAlertsToGroups(groups, "Low Balance Alert: " + account, g -> alertEmail.apply(g.getName()));
        smsNotificationService.sendAlertsToGroups(groups, alertMessageSms);
        
        // Save to Alarm History Audit Table
        try {
            AlarmHistory history = new AlarmHistory();
            history.setAlarmConfigId(config.getId());
            history.setUsername(config.getUsername());
            history.setClientId(config.getClientId());
            history.setCurrentBalance(currentBalance);
            history.setThreshold(config.getMinimumThreshold());
            history.setTriggeredAt(LocalDateTime.now());
            history.setSmsMessage(alertMessageSms);
            history.setEmailMessage(alertEmail.apply(groupNames(groups)));
            alarmHistoryRepository.save(history);
            log.info("Successfully recorded alarm history for config ID {}", config.getId());
        } catch (Exception e) {
            log.error("Failed to save alarm history for config ID {}", config.getId(), e);
        }
    }

    /**
     * [FIX P1-8] Sends a recovery notification when balance climbs back above threshold.
     */
    private void dispatchRecoveryAlerts(AlarmConfig config, BigDecimal currentBalance) {
        Set<ContactGroup> groups = config.getSubscribedGroups();
        if (groups == null || groups.isEmpty()) return;

        String smsg = String.format(
            "RECOVERY: Account balance for '%s' has recovered to %s (threshold: %s). Service is operating normally.",
            config.getUsername(), currentBalance.toString(), config.getMinimumThreshold().toString());

        String account = config.getUsername();
        String balance = currentBalance.toString();
        String threshold = config.getMinimumThreshold().toString();

        emailNotificationService.sendAlertsToGroups(groups, "Balance Recovered: " + account, g -> buildAlertEmailHtml(
            g.getName(),
            "Balance recovered",
            "#067647",
            "The balance for the account below is back above its minimum threshold.",
            account, balance, threshold,
            "No action is required."
        ));
        smsNotificationService.sendAlertsToGroups(groups, smsg);
        log.info("[P1-8] Recovery notifications dispatched for ClientId '{}'.", config.getClientId());
    }

    private static String groupNames(Set<ContactGroup> groups) {
        return groups.stream().map(ContactGroup::getName).sorted().collect(java.util.stream.Collectors.joining(", "));
    }

    /**
     * Plain, readable alert email: greeting, one-line status, a small details table and a closing line.
     * Uses table layout and inline styles only, since most mail clients ignore stylesheets.
     */
    private static String buildAlertEmailHtml(String greetingName, String title, String titleColor, String intro,
                                              String account, String balance, String threshold, String action) {
        String cell = "padding:8px 12px;border-bottom:1px solid #e5e7eb;font-size:14px;";
        return "<!DOCTYPE html><html><body style=\"margin:0;padding:24px;background:#ffffff;"
            + "font-family:Arial,Helvetica,sans-serif;color:#1f2937;line-height:1.6;\">"
            + "<div style=\"max-width:560px;\">"
            + "<p style=\"margin:0 0 16px;font-size:15px;\">Dear " + HtmlUtils.htmlEscape(greetingName) + ",</p>"
            + "<p style=\"margin:0 0 4px;font-size:16px;font-weight:bold;color:" + titleColor + ";\">" + title + "</p>"
            + "<p style=\"margin:0 0 16px;font-size:15px;\">" + intro + "</p>"
            + "<table cellpadding=\"0\" cellspacing=\"0\" style=\"border-collapse:collapse;border-top:1px solid #e5e7eb;margin:0 0 16px;\">"
            + "<tr><td style=\"" + cell + "color:#6b7280;\">Account</td><td style=\"" + cell + "font-weight:bold;\">" + HtmlUtils.htmlEscape(account) + "</td></tr>"
            + "<tr><td style=\"" + cell + "color:#6b7280;\">Current balance</td><td style=\"" + cell + "font-weight:bold;\">" + HtmlUtils.htmlEscape(balance) + "</td></tr>"
            + "<tr><td style=\"" + cell + "color:#6b7280;\">Minimum threshold</td><td style=\"" + cell + "\">" + HtmlUtils.htmlEscape(threshold) + "</td></tr>"
            + "</table>"
            + "<p style=\"margin:0 0 24px;font-size:15px;\">" + action + "</p>"
            + "<p style=\"margin:0;font-size:14px;color:#6b7280;\">Best regards,<br>ITDS Monitoring System</p>"
            + "</div></body></html>";
    }

    /**
     * Public method to allow manual triggering of the balance monitor via API or UI.
     */
    public void triggerManualCheck() {
        log.info("Manual balance check triggered by user.");
        monitorBalancesInternal(true);
    }

    /**
     * Save or update a balance record in the database for historical tracking.
     * Instead of creating a new record on every check, we update the existing record
     * for this alarm config to save database space.
     */
    private void saveBalanceRecord(AlarmConfig config, BigDecimal balance, LocalDateTime checkedAt, boolean alertTriggered) {
        try {
            // Try to find existing record for this alarm config
            BalanceRecord record = balanceRecordRepository.findFirstByAlarmConfigIdOrderByIdDesc(config.getId())
                    .orElse(new BalanceRecord());
            
            // Update all fields (whether new or existing)
            record.setAlarmConfigId(config.getId());
            record.setUsername(config.getUsername());
            record.setClientId(config.getClientId());
            record.setBalance(balance);
            record.setThreshold(config.getMinimumThreshold());
            record.setCheckedAt(checkedAt);
            record.setIsBelowThreshold(balance.compareTo(config.getMinimumThreshold()) < 0);
            record.setAlertTriggered(alertTriggered);
            
            balanceRecordRepository.save(record);
            
            if (record.getId() != null) {
                log.debug("Successfully updated balance record ID {} for ClientId: {}, Balance: {}", 
                        record.getId(), config.getClientId(), balance);
            } else {
                log.debug("Successfully created new balance record for ClientId: {}, Balance: {}", 
                        config.getClientId(), balance);
            }
        } catch (Exception e) {
            log.error("Failed to save balance record for config ID {}", config.getId(), e);
            // Don't throw - we don't want balance record failures to stop monitoring
        }
    }

    /**
     * Update the balance record to mark that an alert was triggered.
     * Since we now only keep one record per alarm config, we can directly find and update it.
     */
    private void updateBalanceRecordAlertStatus(Long alarmConfigId, LocalDateTime checkedAt) {
        try {
            // Find the current record for this config
            balanceRecordRepository.findFirstByAlarmConfigIdOrderByIdDesc(alarmConfigId)
                .ifPresent(record -> {
                    // Verify it's from the current check (within 1 second tolerance)
                    if (record.getCheckedAt().isAfter(checkedAt.minusSeconds(1)) && 
                        record.getCheckedAt().isBefore(checkedAt.plusSeconds(1))) {
                        record.setAlertTriggered(true);
                        balanceRecordRepository.save(record);
                        log.debug("Updated balance record alert status for alarm config ID: {}", alarmConfigId);
                    }
                });
        } catch (Exception e) {
            log.error("Failed to update balance record alert status for config ID {}", alarmConfigId, e);
        }
    }
}
