package com.example.ussdGateway.service;

import com.example.ussdGateway.api.response.LiveLogUpdate;
import com.example.ussdGateway.config.SshConfigProperties;
import com.example.ussdGateway.api.response.LogEvent;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LiveTraceService {

    @Value("${ussd.log.file-pattern:TIBCO-%s.log}")
    private String logFilePattern;

    private final SshConfigProperties sshConfigProperties;
    private final UssdServerRepository ussdServerRepository;
    private final SshConnectionManager sshConnectionManager;

    private static final Pattern CALL_ID_PATTERN = Pattern.compile("([a-f0-9]{24})");
    private static final Pattern LOG_LINE_START = Pattern.compile("^\\[\\d{4}-\\d{2}-\\d{2}");
    private static final long POLL_INTERVAL_MS = 50;
    // Flush a pending log block after this much silence on the tail
    private static final long IDLE_FLUSH_MS = 300;
    // An HTML body without its closing tag may still be arriving — wait longer before flushing it
    private static final long HTML_BLOCK_MAX_WAIT_MS = 3000;

    public Flux<LiveLogUpdate> streamLogs(Map<String, String> serverIdToIp, String username, String password, List<String> msisdns) {
        // Validate all inputs before any SSH connection is made
        InputSanitizer.validateMsisdns(msisdns);
        InputSanitizer.validateNodes(serverIdToIp);
        InputSanitizer.validateSshUsername(username);

        // [FIX P2-15] Pre-compile MSISDNs into a single Pattern — O(1) per log line vs O(n) anyMatch
        Pattern msisdnPattern = Pattern.compile(
            msisdns.stream().map(Pattern::quote).collect(Collectors.joining("|")));

        List<Flux<LiveLogUpdate>> fluxes = new ArrayList<>();
        serverIdToIp.forEach((id, ip) -> {
            fluxes.add(createServerStream(id, ip, username, password, msisdns, msisdnPattern));
        });

        return Flux.merge(fluxes);
    }

    private Flux<LiveLogUpdate> createServerStream(String serverId, String serverIp, String username, String password, List<String> msisdns, Pattern msisdnPattern) {
        Sinks.Many<LiveLogUpdate> sink = Sinks.many().unicast().onBackpressureBuffer();
        
        // Bounded set to prevent memory leak (F-14) - stores max 10,000 active call IDs
        // Note: single-threaded access per server — no synchronization needed
        Set<String> trackedCallIds = Collections.newSetFromMap(new LinkedHashMap<String, Boolean>(2000, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > 10000;
            }
        });
        
        // Bounded session info map (F-14)
        // Note: single-threaded access per server — no synchronization needed
        Map<String, String[]> sessionInfo = new LinkedHashMap<String, String[]>(2000, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String[]> eldest) {
                return size() > 10000;
            }
        };

        // [FIX LT-2/LT-7] Shared references for cancel handler to close SSH resources directly
        final java.util.concurrent.atomic.AtomicReference<Session> sshSessionRef = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<ChannelExec> channelRef = new java.util.concurrent.atomic.AtomicReference<>();

        Thread streamThread = Thread.ofVirtual().unstarted(() -> {
            // [FIX LT-7] Track whether semaphore was actually acquired to prevent over-release
            boolean connectionAcquired = false;
            Session sshSession = null;
            ChannelExec channel = null;
            try {
                JSch jsch = new JSch();

                // Load known hosts file if configured
                if (sshConfigProperties.hasKnownHostsFile()) {
                    jsch.setKnownHosts(sshConfigProperties.getKnownHostsFile());
                }

                sshSession = jsch.getSession(username, serverIp, 22);
                sshSessionRef.set(sshSession);
                sshSession.setPassword(password);
                Properties config = new Properties();
                config.put("StrictHostKeyChecking", sshConfigProperties.toJschValue());
                sshSession.setConfig(config);
                
                sshConnectionManager.acquireConnection(serverIp);
                connectionAcquired = true;
                sshSession.connect(10000);

                // Look up the server's log path from the database
                UssdServer serverEntity = ussdServerRepository.findById(Long.parseLong(serverId))
                        .orElseThrow(() -> new RuntimeException("Server not found: " + serverId));
                String logBasePath = serverEntity.getLogPath();
                if (logBasePath == null || logBasePath.isBlank()) {
                    throw new RuntimeException("Log path not configured for server: " + serverEntity.getName());
                }
                // [FIX H-1/CC-6] Validate log path against safe pattern
                InputSanitizer.validateSafeShellValue(logBasePath);

                String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                String logFile = logBasePath + "/" + String.format(logFilePattern, date);
                // [FIX LT-3] Reduced max timeout from 4h to 30 minutes for production safety
                String command = "timeout 1800 tail -F -n 0 '" + InputSanitizer.shellEscape(logFile) + "'";

                channel = (ChannelExec) sshSession.openChannel("exec");
                channelRef.set(channel);
                channel.setCommand(command);
                BufferedReader reader = new BufferedReader(new InputStreamReader(channel.getInputStream()));
                channel.connect();

                String currentBlock = null;
                // [FIX LT-3] Reduced absolute expiry from 4 hours to 30 minutes
                long absoluteExpiry = System.currentTimeMillis() + (30 * 60 * 1000);
                // [FIX LT-4] Max block size: 50KB to prevent unbounded string growth
                final int MAX_BLOCK_SIZE = 50 * 1024;
                long lastDataAt = System.currentTimeMillis();

                // [FIX LT-18] Poll instead of blocking on readLine(). A block used to be emitted only when the
                // NEXT timestamped line arrived, so the last event of a request (e.g. USSD response) stayed
                // hidden until another request wrote to the log. Now a pending block is flushed once the log is idle.
                while (!Thread.currentThread().isInterrupted() && System.currentTimeMillis() < absoluteExpiry) {
                    String line;
                    if (reader.ready()) {
                        line = reader.readLine();
                    } else if (channel.isClosed()) {
                        line = reader.readLine(); // drain whatever is left; null at EOF
                    } else {
                        long idleMs = System.currentTimeMillis() - lastDataAt;
                        if (currentBlock != null && idleMs >= IDLE_FLUSH_MS
                                && (!isUnterminatedHtmlBlock(currentBlock) || idleMs >= HTML_BLOCK_MAX_WAIT_MS)) {
                            processLine(serverId, serverIp, currentBlock, msisdnPattern, trackedCallIds, sessionInfo, sink);
                            currentBlock = null;
                        }
                        try {
                            Thread.sleep(POLL_INTERVAL_MS);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        continue;
                    }
                    if (line == null) break;
                    lastDataAt = System.currentTimeMillis();

                    if (LOG_LINE_START.matcher(line).find()) {
                        if (currentBlock != null) {
                            processLine(serverId, serverIp, currentBlock, msisdnPattern, trackedCallIds, sessionInfo, sink);
                        }
                        currentBlock = line;
                    } else {
                        if (currentBlock != null) {
                            // [FIX LT-4] Discard oversized blocks to prevent OOM
                            if (currentBlock.length() + line.length() + 1 > MAX_BLOCK_SIZE) {
                                log.warn("Discarding oversized log block ({} bytes) on server {}", currentBlock.length(), serverIp);
                                currentBlock = null;
                                continue;
                            }
                            currentBlock += "\n" + line;
                            if (line.trim().equalsIgnoreCase("</html>")) {
                                processLine(serverId, serverIp, currentBlock, msisdnPattern, trackedCallIds, sessionInfo, sink);
                                currentBlock = null;
                            }
                        }
                    }
                }
                if (currentBlock != null) {
                    processLine(serverId, serverIp, currentBlock, msisdnPattern, trackedCallIds, sessionInfo, sink);
                }

            } catch (Exception e) {
                log.error("Live trace error for {} ({}): {}", serverId, serverIp, e.getMessage());
                LogEvent errEvent = new LogEvent(java.time.Instant.now().toString(), "ERROR", e.getMessage(), null, e.getMessage());
                sink.tryEmitNext(new LiveLogUpdate("SYSTEM_ERROR", serverIp, "ERROR-" + serverId, "", "", "", errEvent));
            } finally {
                log.info("Closing live trace SSH session for {} ({})", serverId, serverIp);
                if (channel != null) channel.disconnect();
                if (sshSession != null && sshSession.isConnected()) {
                    sshSession.disconnect();
                }
                
                // [FIX LT-7] Only release if we actually acquired the permit
                if (connectionAcquired) {
                    try {
                        sshConnectionManager.releaseConnection(serverIp);
                    } catch (Exception ignored) {}
                }
                
                sink.tryEmitComplete();
            }
        });

        streamThread.start();

        return sink.asFlux()
                .doOnCancel(() -> {
                    log.info("Live trace flux cancelled for {} ({}), closing SSH resources directly.", serverId, serverIp);
                    // [FIX LT-2] Close SSH channel/session directly — don't rely on thread interrupt
                    // for blocking readLine() which may not respond to interrupts.
                    try {
                        ChannelExec ch = channelRef.get();
                        if (ch != null) ch.disconnect();
                    } catch (Exception ignored) {}
                    try {
                        Session sess = sshSessionRef.get();
                        if (sess != null && sess.isConnected()) sess.disconnect();
                    } catch (Exception ignored) {}
                    streamThread.interrupt();
                });
    }

    private void processLine(String serverId, String serverIp, String line, Pattern msisdnPattern, Set<String> trackedCallIds, 
                             Map<String, String[]> sessionInfo, Sinks.Many<LiveLogUpdate> sink) {
        
        String callId = extractCallId(line);
        if (callId == null) return;

        // [FIX P2-15] Use pre-compiled Pattern instead of O(n) anyMatch(line::contains)
        boolean hasTargetMsisdn = msisdnPattern.matcher(line).find();

        if (hasTargetMsisdn && !trackedCallIds.contains(callId)) {
            trackedCallIds.add(callId);
        }

        if (trackedCallIds.contains(callId)) {
            LogEvent event = parseLineToEvent(line);
            if (event != null) {
                String[] info = sessionInfo.computeIfAbsent(callId, k -> new String[3]);
                
                if (line.contains("USSD ENTRY:")) {
                    info[0] = extractJsonValue(line, "msisdn");
                    String shortCode = extractJsonValue(line, "ussdstr");
                    String callSource = "Menu Subscription";
                    if (shortCode == null || shortCode.isEmpty()) {
                        shortCode = extractJsonValue(line, "originalRequest");
                        callSource = "Direct Subscription";
                    }
                    info[1] = shortCode;
                    info[2] = callSource;
                }
                
                sink.tryEmitNext(new LiveLogUpdate(serverId, serverIp, callId, info[0], info[1], info[2], event));
            }
        }
    }

    private boolean isUnterminatedHtmlBlock(String block) {
        String lower = block.toLowerCase();
        return lower.contains("<html") && !lower.contains("</html>");
    }

    private String extractCallId(String line) {
        String exactCallId = extractJsonValue(line, "callID");
        if (exactCallId != null && exactCallId.length() == 24) return exactCallId;
        
        Matcher m = CALL_ID_PATTERN.matcher(line);
        if (m.find()) return m.group(1);
        return null;
    }

    private LogEvent parseLineToEvent(String line) {
        String timestamp = extractTimeStamp(line);
        if (line.contains("USSD ENTRY:")) {
            String shortCode = extractJsonValue(line, "ussdstr");
            if (shortCode == null || shortCode.isEmpty()) {
                shortCode = extractJsonValue(line, "originalRequest");
            }
            return new LogEvent(timestamp, "USSD ENTRY", "Originating Request: " + (shortCode != null ? shortCode : "N/A"), extractPayload(line), line);
        } else if (line.contains("HTTP request:")) {
            String url = extractHttpUrl(line);
            return new LogEvent(timestamp, "HTTP REQUEST", "POST " + url, extractPayload(line), line);
        } else if (line.contains("HTTP response:")) {
            String msg = extractJsonValue(line, "message");
            String preview = (msg != null ? "Status: " + msg : "Response received");
            if (msg == null && line.toLowerCase().contains("<html")) {
                preview = "Gateway/HTML Error";
            }
            return new LogEvent(timestamp, "HTTP RESPONSE", preview, extractPayload(line), line);
        } else if (line.contains("USSD response:")) {
            Matcher m = Pattern.compile("\"responseCode\":(\\d+)").matcher(line);
            String status = m.find() ? m.group(1) : "Unknown";
            return new LogEvent(timestamp, "USSD RESPONSE", "USSD Gateway Status: " + status, extractPayload(line), line);
        } else if (line.contains("CDR Save Response")) {
            return new LogEvent(timestamp, "CDR LOG", "CDR Record Processed", extractPayload(line), line);
        }
        return null;
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

    private String extractPayload(String line) {
        int jsonStart = line.indexOf("{");
        if (jsonStart > -1) {
            return line.substring(jsonStart).trim();
        }
        int htmlStart = line.toLowerCase().indexOf("<html");
        if (htmlStart > -1) {
            return line.substring(htmlStart).trim();
        }
        return null;
    }
    
    private String extractHttpUrl(String line) {
        int urlStart = line.indexOf("URL:") + 4;
        int bodyStart = line.indexOf("Body:");
        if (urlStart > 3 && bodyStart > urlStart) {
            return line.substring(urlStart, bodyStart).trim();
        }
        return "Unknown URL";
    }
}
