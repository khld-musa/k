package com.example.kraken_apn.service;

import com.example.kraken_apn.dto.ApnSendResponseDto;
import com.example.kraken_apn.entity.ApnRequest;
import com.example.kraken_apn.repository.ApnRequestRepository;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class ApnService {

    private final ApnRequestRepository apnRequestRepository;
    private final ObjectMapper objectMapper;

    @Value("${apn.api.url:http://10.55.245.100/api/dmc/send-apn}")
    private String apiUrl;

    @Value("${apn.api.key:AIzaSyDaGmWKa4JsXZ-HjGw7ISLn_3namBGewQe}")
    private String apiKey;

    @Value("${apn.api.connect-timeout-ms:2000}")
    private int connectTimeoutMs;

    @Value("${apn.api.read-timeout-ms:30000}")
    private int readTimeoutMs;

    @Value("${apn.api.max-connections:150}")
    private int maxConnections;

    @Value("${apn.api.rate-limit-tps:60}")
    private int rateLimitTps;

    @Value("${apn.api.rate-limit-timeout-ms:200}")
    private int rateLimitTimeoutMs;

    @Value("${apn.api.max-concurrent-calls:150}")
    private int maxConcurrentCalls;

    private static final String DEFAULT_RETRY_MESSAGE = "الرجاء المحاولة لاحقاً / Please try again later";

    private final AtomicInteger activeConcurrentCalls = new AtomicInteger(0);

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

        log.info("ApnService initialized with URL: {}, Rate Limit: {} TPS, Read Timeout: {}ms, Max Concurrent: {}",
                apiUrl, rateLimitTps, readTimeoutMs, maxConcurrentCalls);
    }

    /**
     * Synchronous real-time endpoint for USSD traffic.
     * Extracts imsi and msisdn flexibly from incoming payload, checks circuit breaker,
     * concurrency gate and rate limiter, calls downstream APN API, logs asynchronously, and responds.
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

        // 2. Concurrency Gate: Prevents thread starvation under sudden traffic spikes (<1ms rejection)
        if (activeConcurrentCalls.get() >= maxConcurrentCalls) {
            log.warn("APN Concurrency cap reached (>={} calls). Fast rejecting for MSISDN: {}", maxConcurrentCalls, msisdn);
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

            JsonNode responseJson = parseJsonLenient(responseStr);
            boolean success = resolveSuccess(responseJson, httpStatus);

            String apiMessage = null;
            if (responseJson.hasNonNull("message")) {
                apiMessage = responseJson.path("message").asText().trim();
            }

            String finalMessage = (apiMessage != null && !apiMessage.isBlank())
                    ? apiMessage
                    : (success ? "APN sent successfully" : "Failed to send APN.");

            String status = success ? "SUCCESS" : "FAILED";
            circuitBreaker.onSuccess(latency, TimeUnit.MILLISECONDS);
            safeSaveLogAsync(callId, msisdn, imsi, status, finalMessage, httpStatus, latency, rawJson, responseStr);

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
                    || (ex.getMessage() != null && ex.getMessage().toLowerCase().contains("timeout"))) {
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

    private String extractField(Map<String, Object> map, String... keys) {
        if (map == null) return null;
        for (String k : keys) {
            if (map.containsKey(k) && map.get(k) != null) {
                return String.valueOf(map.get(k)).trim();
            }
        }
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
}
