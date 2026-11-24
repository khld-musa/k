package com.example.ussdGateway.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import java.time.Duration;


import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;

@Service
@Slf4j
public class SesmeApiClient {

    private final RestTemplate restTemplate;
    private final String defaultSesmeApiUrl;
    private final ObjectMapper objectMapper;
    private final SystemSettingService systemSettingService;

    public SesmeApiClient(
            @Value("${sesme.api.url:http://localhost:5000/v1/balance}") String defaultSesmeApiUrl,
            RestTemplateBuilder restTemplateBuilder,
            SystemSettingService systemSettingService) {
        this.restTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(10))
                .setReadTimeout(Duration.ofSeconds(60))
                .build();
        this.defaultSesmeApiUrl = defaultSesmeApiUrl;
        this.objectMapper = new ObjectMapper();
        this.systemSettingService = systemSettingService;
    }


    /**
     * Calls the external Sesme API to retrieve the current credit balance.
     * production implementation using RestTemplate.
     */
    // [FIX P1-6] @Retry retries transient failures before the circuit breaker trips
    @Retry(name = "sesmeApi")
    @CircuitBreaker(name = "sesmeApi", fallbackMethod = "getBalanceFallback")
    public BigDecimal getBalance(String clientId, String apiKey) {
        try {
            log.info("Fetching balance from Sesme API for ClientId: {}", clientId);
            
            HttpHeaders headers = new HttpHeaders();
            headers.set("Accept", "*/*");
            HttpEntity<Void> request = new HttpEntity<>(headers);
            
            
            String currentApiUrl = systemSettingService.getSettingValue("sesme.api.url", this.defaultSesmeApiUrl);

            // Build the URI exactly as per documentation: ?ApiKey=...&ClientId=...
            // Using .build().toUri() prevents RestTemplate from double-encoding the URL later.
            java.net.URI uri = org.springframework.web.util.UriComponentsBuilder.fromHttpUrl(currentApiUrl)
                    .queryParam("ApiKey", apiKey)
                    .queryParam("ClientId", clientId)
                    .build()
                    .toUri();
            
            ResponseEntity<Map> response = restTemplate.exchange(uri, org.springframework.http.HttpMethod.GET, request, Map.class);

            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Object> body = response.getBody();
                Object errorCodeObj = body.get("ErrorCode");
                
                if (errorCodeObj != null && "0".equals(errorCodeObj.toString())) {
                    List<?> dataList = (List<?>) body.get("Data");
                    if (dataList != null && !dataList.isEmpty()) {
                        Map<?, ?> dataItem = (Map<?, ?>) dataList.get(0);
                        Object creditsObj = dataItem.get("Credits");
                        
                        if (creditsObj != null) {
                            String creditsStr = creditsObj.toString();
                            // Strip away any non-numeric characters (like 'SDG') keeping only digits and decimal point
                            String numericStr = creditsStr.replaceAll("[^0-9.]", "");
                            if (!numericStr.isEmpty()) {
                                return new BigDecimal(numericStr);
                            }
                        }
                    }
                }
                
                log.error("Balance/Credits key not found or ErrorCode not 0 in Sesme API response for clientId: {}. Body: {}", clientId, body);
                throw new RuntimeException("Invalid API response: balance field missing or ErrorCode not success");
            } else {
                log.error("Sesme API returned non-success code: {} for clientId: {}", response.getStatusCode(), clientId);
                throw new RuntimeException("External API Error: " + response.getStatusCode());
            }
            
        } catch (Exception e) {
            log.error("Failed to fetch balance from Sesme API for clientId: {}", clientId, e);
            throw new RuntimeException("External API connectivity failure", e);
        }
    }

    /**
     * Fallback method for getBalance when the external API is unreachable or failing.
     */
    public BigDecimal getBalanceFallback(String clientId, String apiKey, Throwable t) {
        log.error("CircuitBreaker triggered for getBalance! Account: {}. Reason: {}", clientId, t.getMessage());
        // Return null so the monitor service knows the live balance could not be determined
        return null;
    }
}
