package com.example.ussdGateway.service;

import com.example.ussdGateway.api.request.McnCheckerRequest;
import com.example.ussdGateway.api.response.LogEvent;
import com.example.ussdGateway.api.response.McnCheckerResponse;
import com.example.ussdGateway.api.response.UssdLogGroup;
import com.example.ussdGateway.config.SshConfigProperties;
import com.example.ussdGateway.entity.McnServer;
import com.example.ussdGateway.repository.McnServerRepository;
import com.example.ussdGateway.util.InputSanitizer;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class McnCheckerService {

    private final McnServerRepository mcnServerRepository;
    private final SshConfigProperties sshConfigProperties;
    private final SshConnectionManager sshConnectionManager;

    private static final DateTimeFormatter FILE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // Notifier "ATI <msisdn> <e|n> {json}[ trailing text]" lines, e.g.:
    // 00:04:36,581 INFO  [com...Checking] (pool-293598-thread-449) ATI 249101269697 e {"mcc":"634",...} Read timed out
    private static final Pattern ATI_LINE_PATTERN = Pattern.compile(
            "^(\\d{2}:\\d{2}:\\d{2},\\d{3})\\s+\\S+\\s+\\[[^\\]]*]\\s+\\([^)]*\\)\\s+ATI\\s+(\\d*)\\s+([en])\\s+(\\{.*})(.*)$");

    // Notifier "SMSC: <Status>,<msisdn>:<refId>" confirmation lines
    private static final Pattern SMSC_LINE_PATTERN = Pattern.compile(
            "^(\\d{2}:\\d{2}:\\d{2},\\d{3})\\s+\\S+\\s+\\[[^\\]]*]\\s+\\([^)]*\\)\\s+SMSC:\\s+(\\w+),(\\d+):(\\S+)\\s*$");

    // Notifier "<msisdn>;<sms text>" content lines
    private static final Pattern SMS_CONTENT_PATTERN = Pattern.compile(
            "^(\\d{2}:\\d{2}:\\d{2},\\d{3})\\s+\\S+\\s+\\[[^\\]]*]\\s+\\([^)]*\\)\\s+(\\d+);(.*)$");

    // SIP "HH:mm:ss,SSS <ip>,<caller>,<callee>,<sipCode>" lines
    private static final Pattern SIP_LINE_PATTERN = Pattern.compile(
            "^(\\d{2}:\\d{2}:\\d{2},\\d{3})\\s+([\\d.]+),(\\d+),(\\d+),(\\d+)\\s*$");

    private static final Map<String, String> SIP_CODE_MEANINGS = Map.of(
            "480", "Subscriber Unreachable",
            "487", "Phone Powered Off"
    );

    public McnCheckerResponse checkLogs(McnCheckerRequest request) {
        String date = request.getDate();
        if (date == null || date.isBlank()) {
            date = LocalDate.now().format(FILE_DATE_FORMAT);
        }
        return executeRealSshCheck(request, date);
    }

    private McnCheckerResponse executeRealSshCheck(McnCheckerRequest request, String date) {
        log.info("Executing real MCN SSH check for MSISDN: {}, date: {}", request.getMsisdn(), date);
        
        // Input validation
        InputSanitizer.validateMsisdns(Collections.singletonList(request.getMsisdn()));
        InputSanitizer.validateDate(date);
        
        McnCheckerResponse.McnCheckerResponseBuilder builder = McnCheckerResponse.builder();
        
        // 1. Fetch SIP Servers
        List<McnServer> sipServers = mcnServerRepository.findByActiveTrueAndServerType(McnServer.ServerType.SIP);
        if (sipServers.isEmpty()) {
            return builder
                    .sipChecked(true)
                    .sipRecordsFound(false)
                    .sipMessage("No active MCN SIP servers found in database. Check configuration.")
                    .notifierChecked(false)
                    .notifierMessage("Notifier check skipped because no SIP servers are configured.")
                    .build();
        }

        // Search SIP servers for records
        List<UssdLogGroup> sipRecords = searchServerLogs(sipServers, request, date);
        builder.sipChecked(true);
        builder.sipRecords(sipRecords);

        // Check if there are any error groups
        List<String> sipErrors = new ArrayList<>();
        sipRecords.removeIf(group -> {
            if ("error".equals(group.getShortCode())) {
                sipErrors.add("Server " + group.getCallSource() + ": " + group.getMsisdn());
                return true;
            }
            return false;
        });

        if (sipRecords.isEmpty()) {
            String errorSuffix = sipErrors.isEmpty() ? "" : " | Connection Errors: " + String.join(", ", sipErrors);
            return builder
                    .sipRecordsFound(false)
                    .sipMessage("SIP logs: No matching records found on any SIP servers (" + sipServers.size() + " checked) for date " + date + errorSuffix)
                    .notifierChecked(false)
                    .notifierMessage("Notifier check skipped because no records were found in the SIP servers.")
                    .build();
        }

        String errorSuffix = sipErrors.isEmpty() ? "" : " (Errors on: " + String.join(", ", sipErrors) + ")";
        builder.sipRecordsFound(true);
        builder.sipMessage("SIP logs: Found " + sipRecords.size() + " matching records across SIP servers." + errorSuffix);

        // 2. Fetch Notifier Servers
        List<McnServer> notifierServers = mcnServerRepository.findByActiveTrueAndServerType(McnServer.ServerType.NOTIFIER);
        if (notifierServers.isEmpty()) {
            return builder
                    .notifierChecked(true)
                    .notifierRecordsFound(false)
                    .notifierMessage("No active MCN Notifier servers found in database. Check configuration.")
                    .build();
        }

        // Search Notifier servers for records
        List<UssdLogGroup> notifierRecords = searchServerLogs(notifierServers, request, date);
        builder.notifierChecked(true);
        builder.notifierRecords(notifierRecords);

        // Check if there are any error groups
        List<String> notifierErrors = new ArrayList<>();
        notifierRecords.removeIf(group -> {
            if ("error".equals(group.getShortCode())) {
                notifierErrors.add("Server " + group.getCallSource() + ": " + group.getMsisdn());
                return true;
            }
            return false;
        });

        if (notifierRecords.isEmpty()) {
            String nErrorSuffixEmpty = notifierErrors.isEmpty() ? "" : " | Connection Errors: " + String.join(", ", notifierErrors);
            return builder
                    .notifierRecordsFound(false)
                    .notifierMessage("Notifier logs: No matching records found on any Notifier servers (" + notifierServers.size() + " checked) for date " + date + nErrorSuffixEmpty)
                    .build();
        }

        String nErrorSuffix = notifierErrors.isEmpty() ? "" : " (Errors on: " + String.join(", ", notifierErrors) + ")";
        builder.notifierRecordsFound(true);
        builder.notifierMessage("Notifier logs: Found " + notifierRecords.size() + " notification sequences. Displaying records." + nErrorSuffix);
        return builder.build();
    }

    private List<UssdLogGroup> searchServerLogs(List<McnServer> servers, McnCheckerRequest request, String date) {
        List<UssdLogGroup> results = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();

        for (McnServer server : servers) {
            Thread thread = Thread.ofVirtual().start(() -> {
                try {
                    List<UssdLogGroup> serverResults = searchSingleServer(server, request, date);
                    results.addAll(serverResults);
                } catch (Exception e) {
                    log.error("MCN check error on server {} ({}): {}", server.getName(), server.getIpAddress(), e.getMessage());
                    UssdLogGroup errorGroup = new UssdLogGroup(server.getId().toString(), server.getIpAddress(), "error");
                    errorGroup.setShortCode("error");
                    errorGroup.setCallSource(server.getName());
                    
                    String safeErrorMessage = "Connection or execution error";
                    if (e.getMessage() != null && e.getMessage().toLowerCase().contains("auth fail")) {
                        safeErrorMessage = "SSH Authentication failed";
                    }
                    errorGroup.setMsisdn(safeErrorMessage);
                    results.add(errorGroup);
                }
            });
            threads.add(thread);
        }

        // Remote commands run with up to a 60s timeout (see the awk pass below), so the local
        // join must wait at least that long to avoid truncating results or leaving orphaned
        // virtual threads that keep mutating `results` after the response has been built.
        for (Thread t : threads) {
            try {
                t.join(java.time.Duration.ofSeconds(70));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        return results;
    }

    private List<UssdLogGroup> searchSingleServer(McnServer server, McnCheckerRequest request, String date) throws Exception {
        String username = request.getUsername();
        String password = request.getPassword();

        // Fall back to the server's stored credentials independently for each field, so a
        // caller can supply just a username (or just a password) and still rely on the stored
        // value for the other.
        if (username == null || username.isBlank()) {
            username = server.getSshUsername();
        }
        if (password == null || password.isBlank()) {
            password = server.getSshPassword();
        }

        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new IllegalArgumentException("No SSH credentials provided or stored for server: " + server.getName());
        }

        username = InputSanitizer.validateSshUsername(username);

        JSch jsch = new JSch();
        if (sshConfigProperties.hasKnownHostsFile()) {
            jsch.setKnownHosts(sshConfigProperties.getKnownHostsFile());
        }

        Session session = jsch.getSession(username, server.getIpAddress(), 22);
        session.setPassword(password);
        
        Properties config = new Properties();
        config.put("StrictHostKeyChecking", sshConfigProperties.toJschValue());
        session.setConfig(config);

        sshConnectionManager.acquireConnection(server.getIpAddress());
        try {
            session.connect(10000);
            String logBasePath = server.getLogPath();
            if (logBasePath == null || logBasePath.isBlank()) {
                throw new RuntimeException("Log path not configured for server: " + server.getName());
            }
            InputSanitizer.validateSafeShellValue(logBasePath);

            String msisdn = request.getMsisdn();
            LocalDate parsedDate = InputSanitizer.validateDate(date);
            String logFile = logBasePath + "/" + resolveLogFileName(parsedDate);

            // Match the MSISDN as a standalone token (not a substring of a longer number).
            // '+' is escaped since it is a quantifier metacharacter in POSIX extended regex.
            String msisdnPattern = "(^|[^0-9])" + msisdn.replace("+", "\\+") + "([^0-9]|$)";
            String grepCommand = "timeout 30 grep -E '" + InputSanitizer.shellEscape(msisdnPattern) + "' '" + InputSanitizer.shellEscape(logFile) + "' | head -n 2000";
            String stdout = executeSshCommand(session, grepCommand);

            if (stdout.isBlank()) {
                return Collections.emptyList();
            }

            List<String> lines = new ArrayList<>();
            for (String line : stdout.split("\n")) {
                if (!line.isBlank()) {
                    lines.add(line);
                }
            }

            UssdLogGroup group = server.getServerType() == McnServer.ServerType.SIP
                    ? parseSipLines(server, msisdn, lines, date)
                    : parseNotifierLines(server, msisdn, lines, date);

            if (group.getEvents().isEmpty()) {
                return Collections.emptyList();
            }
            return List.of(group);

        } finally {
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
            sshConnectionManager.releaseConnection(server.getIpAddress());
        }
    }

    private String executeSshCommand(Session session, String command) throws Exception {
        ChannelExec channel = (ChannelExec) session.openChannel("exec");
        channel.setCommand(command);
        InputStream in = channel.getInputStream();
        channel.connect();

        StringBuilder stdout = new StringBuilder();
        byte[] tmp = new byte[4096];
        while (true) {
            while (in.available() > 0) {
                int i = in.read(tmp, 0, tmp.length);
                if (i < 0) break;
                stdout.append(new String(tmp, 0, i));
            }
            if (channel.isClosed()) {
                if (in.available() > 0) continue;
                break;
            }
            Thread.sleep(50);
        }
        channel.disconnect();
        return stdout.toString();
    }

    private String resolveLogFileName(LocalDate date) {
        if (date.equals(LocalDate.now())) {
            return "MCN.log";
        }
        return "MCN.log." + date.format(FILE_DATE_FORMAT);
    }

    private UssdLogGroup parseNotifierLines(McnServer server, String msisdn, List<String> lines, String date) {
        UssdLogGroup group = new UssdLogGroup(server.getId().toString(), server.getIpAddress(), "notifier-" + msisdn);
        group.setMsisdn(msisdn);
        group.setShortCode("MCN");
        group.setCallSource(server.getName() + " (Notifier)");

        List<LogEvent> events = new ArrayList<>();
        boolean smsSuccess = false;
        String smscRef = null;
        boolean lastAtiTimeout = false;
        String lastAtiState = null;

        for (String line : lines) {
            Matcher ati = ATI_LINE_PATTERN.matcher(line);
            if (ati.matches()) {
                if (!msisdn.equals(ati.group(2))) {
                    continue;
                }
                String ts = date + " " + ati.group(1);
                String flag = ati.group(3);
                String json = ati.group(4);
                String trailing = ati.group(5) == null ? "" : ati.group(5).trim();
                if ("e".equals(flag)) {
                    lastAtiTimeout = true;
                    lastAtiState = null;
                    String details = "ATI lookup timed out" + (trailing.isEmpty() ? "" : " (" + trailing + ")");
                    events.add(new LogEvent(ts, "ATI TIMEOUT", details, json, line));
                } else {
                    lastAtiTimeout = false;
                    lastAtiState = extractJsonField(json, "subscriberState");
                    String details = "ATI lookup result: " + (lastAtiState != null ? lastAtiState : "unknown");
                    events.add(new LogEvent(ts, "ATI RESULT", details, json, line));
                }
                continue;
            }

            Matcher smsc = SMSC_LINE_PATTERN.matcher(line);
            if (smsc.matches()) {
                if (!msisdn.equals(smsc.group(3))) {
                    continue;
                }
                String ts = date + " " + smsc.group(1);
                String status = smsc.group(2);
                String ref = smsc.group(4);
                if ("Success".equalsIgnoreCase(status)) {
                    smsSuccess = true;
                    smscRef = ref;
                }
                events.add(new LogEvent(ts, "SMSC SEND", "SMSC " + status + " (ref " + ref + ")", null, line));
                continue;
            }

            Matcher sms = SMS_CONTENT_PATTERN.matcher(line);
            if (sms.matches()) {
                if (!msisdn.equals(sms.group(2))) {
                    continue;
                }
                String ts = date + " " + sms.group(1);
                String text = sms.group(3).trim();
                String preview = text.length() > 100 ? text.substring(0, 100) + "..." : text;
                events.add(new LogEvent(ts, "SMS CONTENT", preview, text, line));
            }
        }

        events.sort(Comparator.comparing(LogEvent::getTimestamp));
        group.getEvents().addAll(events);
        for (LogEvent event : events) {
            group.getLogs().add(event.getRawLine());
        }

        if (!events.isEmpty()) {
            group.setStartTime(events.get(0).getTimestamp());
        }

        if (smsSuccess) {
            group.setStatus("SUCCESS");
            group.setResultMessage("SMS Delivered" + (smscRef != null ? " (ref " + smscRef + ")" : ""));
        } else if (lastAtiTimeout) {
            group.setStatus("ERROR");
            group.setResultMessage("ATI Read Timeout - no SMS dispatch recorded");
        } else if (lastAtiState != null) {
            group.setStatus("NOT_SENT");
            group.setResultMessage("Subscriber Not Reachable (" + lastAtiState + ")");
        } else if (!events.isEmpty()) {
            group.setStatus("UNKNOWN");
            group.setResultMessage("Matched log lines found but no conclusive ATI/SMSC result parsed");
        }

        return group;
    }

    private UssdLogGroup parseSipLines(McnServer server, String msisdn, List<String> lines, String date) {
        UssdLogGroup group = new UssdLogGroup(server.getId().toString(), server.getIpAddress(), "sip-" + msisdn);
        group.setMsisdn(msisdn);
        group.setShortCode("MCN");
        group.setCallSource(server.getName() + " (SIP)");

        List<LogEvent> events = new ArrayList<>();

        for (String line : lines) {
            Matcher m = SIP_LINE_PATTERN.matcher(line);
            if (!m.matches()) {
                continue;
            }
            String caller = m.group(3);
            String callee = m.group(4);
            // Only the callee (the subscriber who missed the call) is relevant to the MCN trace.
            if (!msisdn.equals(callee)) {
                continue;
            }
            String ts = date + " " + m.group(1);
            String code = m.group(5);
            String meaning = SIP_CODE_MEANINGS.getOrDefault(code, "SIP " + code);
            events.add(new LogEvent(ts, "SIP CALL EVENT", "Missed call from " + caller + " - " + meaning, null, line));
        }

        events.sort(Comparator.comparing(LogEvent::getTimestamp));
        group.getEvents().addAll(events);
        for (LogEvent event : events) {
            group.getLogs().add(event.getRawLine());
        }

        if (!events.isEmpty()) {
            group.setStartTime(events.get(0).getTimestamp());
            group.setStatus("SUCCESS");
            group.setResultMessage("Call Trigger Logged (" + events.size() + " missed call event(s))");
        }

        return group;
    }

    private String extractJsonField(String json, String field) {
        if (json == null) {
            return null;
        }
        Matcher m = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
