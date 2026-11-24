package com.example.ussdGateway.service;

import com.example.ussdGateway.api.dto.ApnRequestDto;
import com.example.ussdGateway.api.dto.response.ApnReportSummaryDto;
import com.example.ussdGateway.api.dto.response.ApnSendResponseDto;
import com.example.ussdGateway.entity.ApnRequest;
import com.example.ussdGateway.repository.ApnRequestRepository;
import com.example.ussdGateway.util.InputSanitizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.netty.channel.ChannelOption;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ApnService {

    private final ApnRequestRepository apnRequestRepository;
    private final ObjectMapper objectMapper;
    private final jakarta.persistence.EntityManager entityManager;

    @Value("${apn.api.url:http://10.55.245.100/api/dmc/send-apn}")
    private String apiUrl;

    @Value("${apn.api.key:AIzaSyDaGmWKa4JsXZ-HjGw7ISLn_3namBGewQe}")
    private String apiKey;

    @Value("${apn.api.connect-timeout-ms:2000}")
    private int connectTimeoutMs;

    @Value("${apn.api.read-timeout-ms:9000}")
    private int readTimeoutMs;

    @Value("${apn.api.max-connections:50}")
    private int maxConnections;

    @Value("${apn.api.rate-limit-tps:18}")
    private int rateLimitTps;

    @Value("${apn.api.rate-limit-timeout-ms:100}")
    private int rateLimitTimeoutMs;

    private static final String DEFAULT_RETRY_MESSAGE = "الرجاء المحاولة لاحقاً / Please try again later";

    private final java.util.concurrent.atomic.AtomicInteger activeConcurrentCalls = new java.util.concurrent.atomic.AtomicInteger(0);
    private static final int MAX_CONCURRENT_CALLS = 25;

    private record RawApiResponse(int statusCode, String body) {}

    private WebClient webClient;
    private RateLimiter rateLimiter;
    private CircuitBreaker circuitBreaker;

    @PostConstruct
    public void init() {
        ConnectionProvider provider = ConnectionProvider.builder("apn-pool")
                .maxConnections(maxConnections)
                .pendingAcquireTimeout(Duration.ofMillis(rateLimitTimeoutMs))
                .maxIdleTime(Duration.ofSeconds(20))
                .build();

        HttpClient httpClient = HttpClient.create(provider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
                .responseTimeout(Duration.ofMillis(readTimeoutMs));

        this.webClient = WebClient.builder()
                .baseUrl(apiUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("X-API-KEY", apiKey)
                .build();

        RateLimiterConfig rateLimiterConfig = RateLimiterConfig.custom()
                .limitForPeriod(rateLimitTps)
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ofMillis(rateLimitTimeoutMs))
                .build();
        this.rateLimiter = RateLimiter.of("apnRateLimiter", rateLimiterConfig);

        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .slidingWindowSize(50)
                .failureRateThreshold(80.0f)
                .waitDurationInOpenState(Duration.ofSeconds(5))
                .permittedNumberOfCallsInHalfOpenState(3)
                .build();
        this.circuitBreaker = CircuitBreaker.of("apnCircuitBreaker", cbConfig);

        log.info("ApnService initialized with URL: {}, Rate Limit: {} TPS, Read Timeout: {}ms",
                apiUrl, rateLimitTps, readTimeoutMs);
    }

    /**
     * Synchronous real-time endpoint for USSD traffic.
     * Extracts imsi and msisdn flexibly from any incoming payload, checks rate limiter and circuit breaker,
     * calls external APN API within <=4.8s, logs transaction asynchronously, and returns response to USSD.
     */
    public ApnSendResponseDto processUssdApnRequest(Map<String, Object> rawPayload) {
        String rawJson = serializePayload(rawPayload);
        String imsi = extractField(rawPayload, "imsi", "IMSI", "Imsi");
        String msisdn = extractField(rawPayload, "msisdn", "MSISDN", "Msisdn", "phone", "phoneNumber", "digits");
        String callId = extractField(rawPayload, "callID", "callId", "CallID", "call_id");

        if (msisdn == null || msisdn.isBlank() || imsi == null || imsi.isBlank()) {
            log.warn("Missing IMSI or MSISDN in USSD payload: {}", rawJson);
            String err = "البيانات غير مكتملة / Missing IMSI or MSISDN";
            safeSaveLogAsync(callId, msisdn != null ? msisdn : "UNKNOWN", imsi != null ? imsi : "UNKNOWN",
                    "INVALID_INPUT", err, 400, 0L, rawJson, null);
            return buildUssdResponse(callId, msisdn, imsi, false, 400, "INVALID_INPUT", err);
        }

        msisdn = normalizeMsisdn(msisdn);
        imsi = imsi.trim();

        // 1. Fast fail if Circuit Breaker is OPEN (<1ms)
        if (circuitBreaker.getState() == CircuitBreaker.State.OPEN) {
            log.warn("APN Circuit Breaker is OPEN. Fast rejecting call for MSISDN: {}", msisdn);
            safeSaveLogAsync(callId, msisdn, imsi, "CIRCUIT_OPEN", DEFAULT_RETRY_MESSAGE, null, 0L, rawJson, null);
            return buildUssdResponse(callId, msisdn, imsi, false, 503, "CIRCUIT_OPEN", DEFAULT_RETRY_MESSAGE);
        }

        // 2. Concurrency Gate: Prevents Tomcat thread starvation under traffic spikes (<2ms rejection)
        if (activeConcurrentCalls.get() >= MAX_CONCURRENT_CALLS) {
            log.warn("APN Concurrency cap reached (>={} calls). Fast rejecting for MSISDN: {}", MAX_CONCURRENT_CALLS, msisdn);
            safeSaveLogAsync(callId, msisdn, imsi, "THROTTLED", DEFAULT_RETRY_MESSAGE, 429, 0L, rawJson, "Concurrency limit reached");
            return buildUssdResponse(callId, msisdn, imsi, false, 429, "THROTTLED", DEFAULT_RETRY_MESSAGE);
        }

        // 3. Rate Limiting (<20 TPS) with fast rejection
        boolean acquired = rateLimiter.acquirePermission(1);
        if (!acquired) {
            log.warn("APN Rate limit exceeded (>{} TPS) for MSISDN: {}", rateLimitTps, msisdn);
            safeSaveLogAsync(callId, msisdn, imsi, "THROTTLED", DEFAULT_RETRY_MESSAGE, 429, 0L, rawJson, null);
            return buildUssdResponse(callId, msisdn, imsi, false, 429, "RATE_LIMITED", DEFAULT_RETRY_MESSAGE);
        }

        // 4. Make the external API call with active concurrency tracking
        activeConcurrentCalls.incrementAndGet();
        long start = System.currentTimeMillis();
        Map<String, String> requestBody = Map.of("imsi", imsi, "msisdn", msisdn);

        try {
            // Use exchangeToMono so we capture the response body even on 4xx/5xx status codes
            RawApiResponse apiResp = webClient.post()
                    .uri("")
                    .bodyValue(requestBody)
                    .exchangeToMono(clientResponse ->
                            clientResponse.bodyToMono(String.class)
                                    .defaultIfEmpty("{}")
                                    .map(body -> new RawApiResponse(clientResponse.statusCode().value(), body))
                    )
                    .block(Duration.ofMillis(readTimeoutMs));

            long latency = System.currentTimeMillis() - start;
            int httpStatus = apiResp != null ? apiResp.statusCode() : 200;
            String responseStr = apiResp != null ? apiResp.body() : "{}";

            log.info("APN external API replied for MSISDN: {} -> HTTP {} in {}ms, body: {}",
                    msisdn, httpStatus, latency, responseStr);

            // A reply was received from the API, so the call itself worked
            JsonNode responseJson = parseJsonLenient(responseStr);
            boolean success = resolveSuccess(responseJson, httpStatus);

            // Extract the API's actual response message
            String apiMessage = null;
            if (responseJson.hasNonNull("message")) {
                apiMessage = responseJson.path("message").asText().trim();
            }

            // Return the API's message; fallback only if empty
            String finalMessage = (apiMessage != null && !apiMessage.isBlank())
                    ? apiMessage
                    : (success ? "APN sent successfully" : "Failed to send APN.");

            String status = success ? "SUCCESS" : "FAILED";
            circuitBreaker.onSuccess(latency, TimeUnit.MILLISECONDS);
            safeSaveLogAsync(callId, msisdn, imsi, status, finalMessage, httpStatus, latency, rawJson, responseStr);
            log.info("APN response to USSD for MSISDN: {} -> success={}, message={}", msisdn, success, finalMessage);

            int ussdCode = success ? 200 : (httpStatus >= 400 ? httpStatus : 422);
            String statusCode = success ? "0" : (responseJson.hasNonNull("code") ? responseJson.path("code").asText() : "ESB-00051");

            return buildUssdResponse(callId, msisdn, imsi, success, ussdCode, statusCode, finalMessage);

        } catch (Exception ex) {
            long latency = System.currentTimeMillis() - start;
            circuitBreaker.onError(latency, TimeUnit.MILLISECONDS, ex);

            Integer httpStatus = null;
            String status = "FAILED";
            String errorDetail = ex.getMessage();
            String returnedMessage = DEFAULT_RETRY_MESSAGE;

            // If an HTTP exception occurred but has a response body, extract the real API message!
            if (ex instanceof WebClientResponseException wce) {
                httpStatus = wce.getStatusCode().value();
                String errBody = wce.getResponseBodyAsString();
                if (errBody != null && !errBody.isBlank()) {
                    try {
                        JsonNode errJson = objectMapper.readTree(errBody);
                        if (errJson.hasNonNull("message") && !errJson.path("message").asText().isBlank()) {
                            returnedMessage = errJson.path("message").asText().trim();
                        }
                    } catch (Exception ignored) {}
                }
            } else if (ex.getCause() instanceof java.util.concurrent.TimeoutException
                    || ex.getMessage() != null && ex.getMessage().toLowerCase().contains("timeout")) {
                status = "TIMEOUT";
            }

            log.error("Failed to send APN for MSISDN: {}, latency: {}ms, error: {}", msisdn, latency, errorDetail);
            safeSaveLogAsync(callId, msisdn, imsi, status, returnedMessage, httpStatus, latency, rawJson, errorDetail);

            int ussdCode = (httpStatus != null && httpStatus > 0) ? httpStatus : 500;
            String statusCode = "TIMEOUT".equals(status) ? "TIMEOUT" : "HTTP_" + ussdCode;

            return buildUssdResponse(callId, msisdn, imsi, false, ussdCode, statusCode, returnedMessage);
        } finally {
            activeConcurrentCalls.decrementAndGet();
        }
    }

    private ApnSendResponseDto buildUssdResponse(String callId, String msisdn, String imsi,
                                                 boolean success, int responseCode,
                                                 String statusDetailsCode, String message) {
        String txId = UUID.randomUUID().toString();
        Map<String, Object> data = new HashMap<>();
        if (imsi != null && !imsi.isBlank()) data.put("imsi", imsi);
        if (msisdn != null && !msisdn.isBlank()) data.put("msisdn", msisdn);

        return ApnSendResponseDto.builder()
                .responseCode(responseCode)
                .transactionId(txId)
                .callID(callId)
                .statusDetailsStatus(success ? "SUCCESS" : "FAIL")
                .statusDetailsCode(statusDetailsCode)
                .result(message)
                .msisdn(msisdn)
                .responseMessage(message)
                .success(success)
                .message(message)
                .data(data.isEmpty() ? null : data)
                .build();
    }

    private void safeSaveLogAsync(String callId, String msisdn, String imsi, String status,
                                  String responseMessage, Integer httpStatus, Long latencyMs,
                                  String rawRequest, String rawResponse) {
        try {
            saveLogAsync(callId, msisdn, imsi, status, responseMessage, httpStatus, latencyMs, rawRequest, rawResponse);
        } catch (Throwable t) {
            log.error("Failed to enqueue APN log: {}", t.getMessage());
        }
    }

    @Async("apnLogExecutor")
    public void saveLogAsync(String callId, String msisdn, String imsi, String status,
                             String responseMessage, Integer httpStatus, Long latencyMs,
                             String rawRequest, String rawResponse) {
        try {
            ApnRequest entity = ApnRequest.builder()
                    .callId(callId)
                    .msisdn(msisdn)
                    .imsi(imsi)
                    .status(status)
                    .responseMessage(responseMessage)
                    .httpStatus(httpStatus)
                    .latencyMs(latencyMs)
                    .rawRequest(rawRequest)
                    .rawResponse(rawResponse)
                    .build();
            apnRequestRepository.save(entity);
        } catch (Exception e) {
            log.error("Failed to async save APN log: {}", e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public ApnReportSummaryDto getSummary(String startDate, String endDate) {
        LocalDate start = InputSanitizer.validateDate(startDate);
        LocalDate end = InputSanitizer.validateDate(endDate);
        LocalDateTime rangeStart = start.atStartOfDay();
        LocalDateTime rangeEnd = end.atTime(LocalTime.MAX);

        long total = apnRequestRepository.countInRange(rangeStart, rangeEnd);
        long successCount = apnRequestRepository.countByStatusInRange(rangeStart, rangeEnd, "SUCCESS");
        long throttledCount = apnRequestRepository.countByStatusInRange(rangeStart, rangeEnd, "THROTTLED");
        long failedCount = total - successCount - throttledCount;

        double successRate = total == 0 ? 0.0 : (successCount * 100.0) / total;
        Double avgLatency = apnRequestRepository.averageLatencyInRange(rangeStart, rangeEnd);

        List<ApnReportSummaryDto.DailyCount> dailyBreakdown = apnRequestRepository
                .countByDayInRange(rangeStart, rangeEnd).stream()
                .map(row -> new ApnReportSummaryDto.DailyCount(
                        row.getDay().toLocalDate(),
                        row.getTotal() != null ? row.getTotal() : 0,
                        row.getSuccess() != null ? row.getSuccess() : 0,
                        row.getThrottled() != null ? row.getThrottled() : 0,
                        row.getFailed() != null ? row.getFailed() : 0
                ))
                .collect(Collectors.toList());

        List<ApnReportSummaryDto.StatusCount> statusBreakdown = apnRequestRepository
                .countByStatusGroup(rangeStart, rangeEnd).stream()
                .map(row -> new ApnReportSummaryDto.StatusCount(
                        row.getStatus(),
                        row.getTotal() != null ? row.getTotal() : 0
                ))
                .collect(Collectors.toList());

        return ApnReportSummaryDto.builder()
                .startDate(startDate)
                .endDate(endDate)
                .totalRequests(total)
                .successCount(successCount)
                .throttledCount(throttledCount)
                .failedCount(failedCount)
                .successRatePercent(Math.round(successRate * 100.0) / 100.0)
                .averageLatencyMs(avgLatency)
                .dailyBreakdown(dailyBreakdown)
                .statusBreakdown(statusBreakdown)
                .circuitBreakerState(circuitBreaker.getState().name())
                .build();
    }

    @Transactional(readOnly = true)
    public Page<ApnRequestDto> getTransactions(String startDate, String endDate, String status,
                                               String msisdn, String imsi, int page, int size) {
        LocalDate start = InputSanitizer.validateDate(startDate);
        LocalDate end = InputSanitizer.validateDate(endDate);
        LocalDateTime rangeStart = start.atStartOfDay();
        LocalDateTime rangeEnd = end.atTime(LocalTime.MAX);

        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 500));
        Page<ApnRequest> results = apnRequestRepository.search(
                rangeStart, rangeEnd,
                blankToNull(status), formatSearchPattern(msisdn), formatSearchPattern(imsi),
                pageable);
        return results.map(this::toDto);
    }

    @Transactional(readOnly = true)
    public List<ApnRequestDto> getTransactionsForExport(String startDate, String endDate, String status,
                                                        String msisdn, String imsi) {
        LocalDate start = InputSanitizer.validateDate(startDate);
        LocalDate end = InputSanitizer.validateDate(endDate);
        LocalDateTime rangeStart = start.atStartOfDay();
        LocalDateTime rangeEnd = end.atTime(LocalTime.MAX);

        return apnRequestRepository.findAllInRange(rangeStart, rangeEnd,
                        blankToNull(status), formatSearchPattern(msisdn), formatSearchPattern(imsi)).stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    private static final int EXPORT_PAGE_SIZE = 5_000;
    private static final int EXPORT_MAX_ROWS  = 20_000_000;

    /** Paginated streaming export — feeds rows to the callback in 5 000-row pages. */
    @Transactional(readOnly = true)
    public int streamTransactionsForExport(String startDate, String endDate, String status,
                                            String msisdn, String imsi,
                                            Consumer<ApnRequestDto> rowConsumer) {
        LocalDate start = InputSanitizer.validateDate(startDate);
        LocalDate end = InputSanitizer.validateDate(endDate);
        LocalDateTime rangeStart = start.atStartOfDay();
        LocalDateTime rangeEnd = end.atTime(LocalTime.MAX);

        String nStatus = blankToNull(status);
        String nMsisdn = formatSearchPattern(msisdn);
        String nImsi = formatSearchPattern(imsi);

        int totalStreamed = 0;
        int pageNum = 0;
        LocalDateTime cursorTime = rangeEnd;
        long cursorId = Long.MAX_VALUE;

        while (totalStreamed < EXPORT_MAX_ROWS) {
            if (Thread.currentThread().isInterrupted()) {
                log.warn("APN export interrupted by cancellation or timeout; halting stream at {} rows", totalStreamed);
                break;
            }

            List<ApnRequest> page = apnRequestRepository.searchKeyset(
                    rangeStart, rangeEnd, nStatus, nMsisdn, nImsi,
                    cursorTime, cursorId, PageRequest.of(0, EXPORT_PAGE_SIZE));
            if (page.isEmpty()) {
                break;
            }

            for (ApnRequest req : page) {
                rowConsumer.accept(toDto(req));
                totalStreamed++;
            }

            ApnRequest last = page.get(page.size() - 1);
            cursorTime = last.getCreatedAt();
            cursorId = last.getId();
            pageNum++;

            // Clear persistence context to release loaded entity graph and prevent heap bloat / GC freezes
            entityManager.clear();

            if (page.size() < EXPORT_PAGE_SIZE) {
                break;
            }
        }

        if (totalStreamed >= EXPORT_MAX_ROWS) {
            log.warn("APN export capped at {} rows (max {})", totalStreamed, EXPORT_MAX_ROWS);
        }
        log.info("APN export streamed {} rows in {} pages", totalStreamed, pageNum);
        return totalStreamed;
    }

    private ApnRequestDto toDto(ApnRequest req) {
        return ApnRequestDto.builder()
                .id(req.getId())
                .callId(req.getCallId())
                .msisdn(req.getMsisdn())
                .imsi(req.getImsi())
                .status(req.getStatus())
                .responseMessage(req.getResponseMessage())
                .httpStatus(req.getHttpStatus())
                .latencyMs(req.getLatencyMs())
                .createdAt(req.getCreatedAt())
                .build();
    }

    private JsonNode parseJsonLenient(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            return node != null && node.isObject() ? node : objectMapper.createObjectNode();
        } catch (Exception e) {
            log.warn("APN API returned a non-JSON body, falling back to HTTP status: {}", body);
            return objectMapper.createObjectNode();
        }
    }

    private boolean resolveSuccess(JsonNode json, int httpStatus) {
        boolean httpOk = httpStatus >= 200 && httpStatus < 300;
        JsonNode flag = json.get("success");
        if (flag != null && !flag.isNull()) {
            return flag.asBoolean(httpOk);
        }
        String statusText = json.path("status").asText("").trim().toLowerCase();
        if (!statusText.isEmpty()) {
            if (statusText.matches("success|successful|ok|true|sent|done|delivered")) {
                return true;
            }
            if (statusText.matches("fail|failed|failure|error|false|rejected")) {
                return false;
            }
        }
        return httpOk;
    }

    private String normalizeMsisdn(String raw) {
        String digits = raw.replaceAll("\\D", "");
        if (digits.startsWith("0") && digits.length() >= 10) {
            return "249" + digits.substring(1);
        }
        if (digits.length() == 9) {
            return "249" + digits;
        }
        if (digits.startsWith("249")) {
            return digits;
        }
        return digits;
    }

    @SuppressWarnings("unchecked")
    private String extractField(Map<String, Object> map, String... keys) {
        if (map == null) return null;
        for (String k : keys) {
            if (map.containsKey(k) && map.get(k) != null) {
                return String.valueOf(map.get(k)).trim();
            }
        }
        // Check nested data if present
        if (map.get("data") instanceof Map<?, ?> nested) {
            for (String k : keys) {
                if (nested.containsKey(k) && nested.get(k) != null) {
                    return String.valueOf(nested.get(k)).trim();
                }
            }
        }
        return null;
    }

    private String serializePayload(Map<String, Object> map) {
        if (map == null) return null;
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return map.toString();
        }
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank() || "all".equalsIgnoreCase(value.trim())) ? null : value.trim();
    }

    private String formatSearchPattern(String value) {
        if (value == null || value.isBlank() || "all".equalsIgnoreCase(value.trim())) {
            return null;
        }
        return "%" + value.trim() + "%";
    }
}
