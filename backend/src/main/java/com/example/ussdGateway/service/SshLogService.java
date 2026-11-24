package com.example.ussdGateway.service;

import com.example.ussdGateway.api.request.LogSearchRequest;
import com.example.ussdGateway.api.response.LogEvent;
import com.example.ussdGateway.api.response.UssdLogGroup;
import com.example.ussdGateway.api.response.LogSearchResponse;
import com.example.ussdGateway.config.SshConfigProperties;
import com.example.ussdGateway.entity.UssdServer;
import com.example.ussdGateway.repository.UssdServerRepository;
import com.example.ussdGateway.util.InputSanitizer;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import jakarta.annotation.PostConstruct;

@Slf4j
@Service
@RequiredArgsConstructor
public class SshLogService {

    @Value("${ussd.log.file-pattern:TIBCO-%s.log}")
    private String logFilePattern;

    private final SshConfigProperties sshConfigProperties;
    private final UssdServerRepository ussdServerRepository;
    private final SshConnectionManager sshConnectionManager;

    private static final Pattern CALL_ID_PATTERN = Pattern.compile("([a-f0-9]{24})");

    // [FIX P2-17] Fail fast at startup if logFilePattern is misconfigured
    @PostConstruct
    public void validateConfiguration() {
        if (!logFilePattern.contains("%s")) {
            throw new IllegalStateException(
                "[P2-17] Invalid ussd.log.file-pattern: '" + logFilePattern +
                "' — must contain '%s' as a date placeholder (e.g., TIBCO-%s.log)");
        }
        log.info("SshLogService configured with log file pattern: '{}'", logFilePattern);
    }

    /**
     * [FIX SEC-2] Credentials are now supplied by the controller after being resolved
     * from the Redis session. The request body no longer carries a raw password.
     *
     * @param request  search parameters (nodes, date, MSISDNs) — no credentials
     * @param username SSH username (resolved from Redis session or DB fallback)
     * @param password SSH password (resolved from Redis session or DB fallback)
     */
    public LogSearchResponse searchRemoteLogs(LogSearchRequest request, String username, String password) {
        // Validate all user inputs before building any shell commands
        InputSanitizer.validateMsisdns(request.getMsisdns());
        InputSanitizer.validateNodes(request.getNodes());
        InputSanitizer.validateDate(request.getDate());

        // If session credentials are empty, fall back to DB-stored credentials
        if ((username == null || username.isBlank()) && !request.getNodes().isEmpty()) {
            String firstNodeId = request.getNodes().keySet().iterator().next();
            UssdServer server = ussdServerRepository.findById(Long.parseLong(firstNodeId)).orElse(null);
            if (server != null && server.getSshUsername() != null) {
                username = server.getSshUsername();
                password = server.getSshPassword();
            }
        }

        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new IllegalArgumentException("SSH credentials required (neither provided nor stored)");
        }

        InputSanitizer.validateSshUsername(username);

        final String finalUsername = username;
        final String finalPassword = password;
        
        List<UssdLogGroup> aggregatedResults = java.util.Collections.synchronizedList(new ArrayList<>());
        List<String> aggregatedErrors = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();

        request.getNodes().forEach((serverId, serverIp) -> {
            Thread thread = Thread.ofVirtual().start(() -> {
                try {
                    LogSearchRequest nodeRequest = LogSearchRequest.builder()
                            .nodes(Map.of(serverId, serverIp))
                            .msisdns(request.getMsisdns())
                            .date(request.getDate())
                            .sessionId(request.getSessionId()) // propagate sessionId for builder completeness
                            .build();

                    List<UssdLogGroup> results = searchSingleServer(serverId, serverIp, nodeRequest, finalUsername, finalPassword);
                    aggregatedResults.addAll(results);
                } catch (Exception e) {
                    log.error("Error searching logs on server {} ({}): {}", serverId, serverIp, e.getMessage());
                    aggregatedErrors.add(String.format("Server '%s' (%s): %s", serverId, serverIp, e.getMessage()));
                }
            });
            threads.add(thread);
        });

        // Wait for all searches to complete
        for (Thread t : threads) {
            try {
                t.join(java.time.Duration.ofSeconds(20)); 
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // Sort by start time descending
        List<UssdLogGroup> sortedResults = new ArrayList<>(aggregatedResults);
        sortedResults.sort((a, b) -> {
            if (a.getStartTime() == null) return 1;
            if (b.getStartTime() == null) return -1;
            return b.getStartTime().compareTo(a.getStartTime());
        });

        return new LogSearchResponse(sortedResults, new ArrayList<>(aggregatedErrors));
    }

    // [FIX H-2] Strict pattern for validating call IDs extracted from untrusted log data
    private static final Pattern STRICT_CALLID_PATTERN = Pattern.compile("^[a-f0-9]{24}$");
    // [FIX RS-3] Maximum number of call IDs to include in the second-pass awk command
    private static final int MAX_CALL_IDS = 500;

    private List<UssdLogGroup> searchSingleServer(String serverId, String serverIp,
                                                   LogSearchRequest request,
                                                   String username, String password) throws Exception {
        JSch jsch = new JSch();

        // Load known hosts file if configured
        if (sshConfigProperties.hasKnownHostsFile()) {
            jsch.setKnownHosts(sshConfigProperties.getKnownHostsFile());
        }

        Session session = jsch.getSession(username, serverIp, 22);
        session.setPassword(password);
        
        Properties config = new Properties();
        config.put("StrictHostKeyChecking", sshConfigProperties.toJschValue());
        session.setConfig(config);
        
        sshConnectionManager.acquireConnection(serverIp);
        try {
            session.connect(10000);
            // Look up the server's log path from the database
            UssdServer server = ussdServerRepository.findById(Long.parseLong(serverId))
                    .orElseThrow(() -> new RuntimeException("Server not found: " + serverId));
            String logBasePath = server.getLogPath();
            if (logBasePath == null || logBasePath.isBlank()) {
                throw new RuntimeException("Log path not configured for server: " + server.getName());
            }
            // [FIX H-1/CC-6] Validate log path against strict safe pattern
            InputSanitizer.validateSafeShellValue(logBasePath);

            // All MSISDNs are already validated as digits-only by InputSanitizer.
            // Shell-escape each one for defense-in-depth.
            String msisdnRegex = request.getMsisdns().stream()
                    .map(InputSanitizer::shellEscape)
                    .reduce((a, b) -> a + "|" + b)
                    .orElseThrow();

            // Date is validated as yyyy-MM-dd by InputSanitizer — safe for file path
            LocalDate validatedDate = InputSanitizer.validateDate(request.getDate());
            String logFile = logBasePath + "/" + String.format(logFilePattern, validatedDate);

            // [FIX REL-4] Use grep -E (POSIX-standard) instead of deprecated egrep
            String command1 = "timeout 60 grep -E '" + msisdnRegex + "' '" + InputSanitizer.shellEscape(logFile) + "' | head -n 50000";
            CommandResult pass1Result = executeCommand(session, command1);

            if (pass1Result.stderr() != null && pass1Result.stderr().contains("No such file or directory")) {
                throw new RuntimeException("Log file not found for date " + request.getDate() + " (" + logFile + ")");
            }

            Set<String> callIds = extractCallIds(pass1Result.stdout());

            if (callIds.isEmpty()) {
                if (!pass1Result.stderr().isBlank()) {
                     throw new RuntimeException("Bash command failed: " + pass1Result.stderr());
                }
                return Collections.emptyList(); // No exact records found matching that MSISDN
            }

            // [FIX H-2] Validate each call ID strictly — they come from untrusted log data
            callIds.removeIf(id -> !STRICT_CALLID_PATTERN.matcher(id).matches());
            if (callIds.isEmpty()) {
                return Collections.emptyList();
            }

            // [FIX RS-3] Limit call IDs to prevent massive awk regex
            if (callIds.size() > MAX_CALL_IDS) {
                log.warn("Truncating call IDs from {} to {} for server {}", callIds.size(), MAX_CALL_IDS, serverIp);
                callIds = callIds.stream().limit(MAX_CALL_IDS).collect(java.util.stream.Collectors.toSet());
            }

            // Call IDs are now validated hex strings — safe for shell
            String callIdRegex = String.join("|", callIds);
            // [FIX RS-3/RS-4] Prefix awk with timeout
            String awkCommand = String.format("timeout 120 awk -v pat='(%s)' '" +
                "/^\\[[0-9]{4}-[0-9]{2}-[0-9]{2} / { " +
                "  if (in_block) { if (block ~ pat) print block \"\\n---END_BLOCK---\" } " +
                "  in_block=1; block=$0; next " +
                "} " +
                "in_block { block = block \"\\n\" $0 } " +
                "END { if (in_block && block ~ pat) print block \"\\n---END_BLOCK---\" }' '%s'", 
                callIdRegex, InputSanitizer.shellEscape(logFile));
                
            CommandResult pass2Result = executeCommand(session, awkCommand);

            return parseLogsIntoGroups(serverId, serverIp, pass2Result.stdout(), callIds);

        } finally {
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
            sshConnectionManager.releaseConnection(serverIp);
        }
    }

    private record CommandResult(String stdout, String stderr) {}

    // [FIX P0-3] Hard limit on stdout/stderr buffer to prevent OOM from massive log output
    private static final int MAX_STDOUT_BYTES = 50 * 1024 * 1024; // 50 MB
    private static final int MAX_STDERR_BYTES = 1024 * 1024;       // 1 MB

    private CommandResult executeCommand(Session session, String command) throws Exception {
        ChannelExec channel = (ChannelExec) session.openChannel("exec");
        channel.setCommand(command);

        InputStream in = channel.getInputStream();
        InputStream err = channel.getErrStream();
        channel.connect();

        StringBuilder stdoutSb = new StringBuilder();
        StringBuilder stderrSb = new StringBuilder();
        boolean stdoutTruncated = false;
        boolean stderrTruncated = false;
        // [FIX H-7] Increased buffer from 1KB to 8KB for better throughput
        byte[] tmp = new byte[8192];

        while (true) {
            while (in.available() > 0) {
                int i = in.read(tmp, 0, tmp.length);
                if (i < 0) break;
                if (stdoutSb.length() < MAX_STDOUT_BYTES) {
                    stdoutSb.append(new String(tmp, 0, i));
                } else if (!stdoutTruncated) {
                    stdoutTruncated = true;
                    log.warn("[P0-3] stdout buffer exceeded {} bytes — output truncated", MAX_STDOUT_BYTES);
                }
                // else: discard bytes to prevent OOM, but keep draining the stream
            }
            while (err.available() > 0) {
                int i = err.read(tmp, 0, tmp.length);
                if (i < 0) break;
                if (stderrSb.length() < MAX_STDERR_BYTES) {
                    stderrSb.append(new String(tmp, 0, i));
                } else if (!stderrTruncated) {
                    stderrTruncated = true;
                    log.warn("[P0-3] stderr buffer exceeded {} bytes — output truncated", MAX_STDERR_BYTES);
                }
            }
            if (channel.isClosed()) {
                if (in.available() > 0 || err.available() > 0) continue;
                break;
            }
            try {
                Thread.sleep(50);
            } catch (Exception ee) {
            }
        }
        channel.disconnect();
        return new CommandResult(stdoutSb.toString(), stderrSb.toString().trim());
    }

    private Set<String> extractCallIds(String logs) {
        Set<String> callIds = new HashSet<>();
        String[] lines = logs.split("\n");
        for (String line : lines) {
            String exactCallId = extractJsonValue(line, "callID");
            if (exactCallId != null && exactCallId.length() == 24) {
               callIds.add(exactCallId);
               continue;
            }
            Matcher m = CALL_ID_PATTERN.matcher(line);
            while(m.find()) {
                String potentialCallId = m.group(1);
                if (potentialCallId.length() == 24) {
                   callIds.add(potentialCallId);
                }
            }
        }
        return callIds;
    }

    private List<UssdLogGroup> parseLogsIntoGroups(String serverId, String serverIp, String fullLogs, Set<String> callIds) {
        Map<String, UssdLogGroup> groupMap = new HashMap<>();
        
        for (String callId : callIds) {
            groupMap.put(callId, new UssdLogGroup(serverId, serverIp, callId));
        }

        String[] blocks = fullLogs.split("---END_BLOCK---");
        
        for (String blockRaw : blocks) {
            String line = blockRaw.trim();
            if (line.isEmpty()) continue;
            
            Set<String> matchedIdsInBlock = new HashSet<>();
            String exactCallId = extractJsonValue(line, "callID");
            if (exactCallId != null && exactCallId.length() == 24) matchedIdsInBlock.add(exactCallId);
            Matcher matchObj = CALL_ID_PATTERN.matcher(line);
            while(matchObj.find()) {
                String potentialCallId = matchObj.group(1);
                if(potentialCallId.length() == 24) {
                   matchedIdsInBlock.add(potentialCallId);
                }
            }
            
            for (String callId : matchedIdsInBlock) {
                UssdLogGroup group = groupMap.get(callId);
                if (group != null) {
                    group.getLogs().add(line);

                    String timestamp = extractTimeStamp(line);
                    
                    if (line.contains("USSD ENTRY:")) {
                        String msisdn = extractJsonValue(line, "msisdn");
                        if (msisdn != null) group.setMsisdn(msisdn);
                        String shortCode = extractJsonValue(line, "ussdstr");
                        String callSource = "Menu Subscription";
                        if (shortCode == null || shortCode.isEmpty()) {
                            shortCode = extractJsonValue(line, "originalRequest");
                            callSource = "Direct Subscription";
                        }
                        if (shortCode != null) group.setShortCode(shortCode);
                        group.setCallSource(callSource);

                        int jsonStart = line.indexOf("{");
                        String payload = (jsonStart > -1) ? line.substring(jsonStart).trim() : null;
                        
                        LogEvent entry = new LogEvent(timestamp, "USSD ENTRY", "Originating Request: " + (group.getShortCode() != null ? group.getShortCode() : "N/A"), payload, line);
                        group.getEvents().add(entry);
                        group.setStartTime(timestamp);
                    } else if (line.contains("HTTP request:")) {
                        int bodyStart = line.indexOf("Body:");
                        String preview = "HTTP Request";
                        String payload = null;
                        if (bodyStart > -1) {
                            String url = line.substring(line.indexOf("URL:") + 4, bodyStart).trim();
                            preview = "POST " + url;
                            payload = line.substring(bodyStart + 5).trim();
                        }
                        LogEvent req = new LogEvent(timestamp, "HTTP REQUEST", preview, payload, line);
                        group.getEvents().add(req);
                    } else if (line.contains("HTTP response:")) {
                        String statusMsg = extractJsonValue(line, "message");
                        String preview = (statusMsg != null) ? "Status Message: " + statusMsg : "HTTP Response received";
                        int jsonStart = line.indexOf("{");
                        String payload = (jsonStart > -1) ? line.substring(jsonStart).trim() : null;
                        
                        if (payload == null && line.toLowerCase().contains("<html")) {
                            int htmlStart = line.toLowerCase().indexOf("<html");
                            payload = line.substring(htmlStart).trim();
                            preview = "Gateway/HTML Error";
                            group.setStatus("FAILED");
                        } else if (payload != null) {
                            if (payload.contains("\"status\":\"SUCCESS\"")) group.setStatus("SUCCESS");
                            else if (payload.contains("\"status\":\"FAIL\"")) group.setStatus("FAILED");
                        }
                        
                        LogEvent res = new LogEvent(timestamp, "HTTP RESPONSE", preview, payload, line);
                        group.getEvents().add(res);
                    } else if (line.contains("USSD response:")) {
                        Matcher m = Pattern.compile("\"responseCode\":(\\d+)").matcher(line);
                        String status = m.find() ? m.group(1) : "Unknown";
                        if (!status.equals("Unknown")) {
                            group.setStatus(status.equals("200") ? "SUCCESS" : "FAILED");
                        }
                        String result = extractJsonValue(line, "result");
                        if (result != null) group.setResultMessage(result);
                        
                        int jsonStart = line.indexOf("{");
                        String payload = (jsonStart > -1) ? line.substring(jsonStart).trim() : null;
                        LogEvent ussdRes = new LogEvent(timestamp, "USSD RESPONSE", "USSD Gateway Status: " + status, payload, line);
                        group.getEvents().add(ussdRes);
                    } else if (line.contains("CDR Save Response")) {
                        int jsonStart = line.indexOf("{");
                        String payload = (jsonStart > -1) ? line.substring(jsonStart).trim() : null;
                        LogEvent cdr = new LogEvent(timestamp, "CDR LOG", "CDR Record Processed", payload, line);
                        group.getEvents().add(cdr);
                    }
                }
            }
        }

        List<UssdLogGroup> result = new ArrayList<>(groupMap.values());
        result.removeIf(g -> g.getMsisdn() == null || g.getEvents().isEmpty());
        return result;
    }

    private String extractTimeStamp(String line) {
        if (line.startsWith("[")) {
            int endIdx = line.indexOf("]");
            if (endIdx > 0) return line.substring(1, endIdx);
        }
        return null;
    }

    private String extractJsonValue(String jsonStr, String key) {
        String searchField = "\"" + key + "\":\"";
        int startIdx = jsonStr.indexOf(searchField);
        if (startIdx != -1) {
            startIdx += searchField.length();
            int endIdx = jsonStr.indexOf("\"", startIdx);
            if (endIdx != -1) return jsonStr.substring(startIdx, endIdx);
        }
        return null;
    }
}
