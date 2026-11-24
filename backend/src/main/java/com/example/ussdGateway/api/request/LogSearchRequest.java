package com.example.ussdGateway.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LogSearchRequest {
    @NotEmpty(message = "At least one server node is required")
    private Map<String, String> nodes; // mapping of serverId to serverIp

    /**
     * [FIX SEC-2] Opaque session ID returned by /logs/search/start.
     * Raw SSH credentials are NO LONGER accepted here — they must be pre-registered
     * via the start endpoint and will be resolved from Redis by the controller.
     */
    @NotBlank(message = "A valid search session ID is required. Call /logs/search/start first.")
    private String sessionId;

    @NotBlank(message = "Date is required")
    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "Date must be in yyyy-MM-dd format")
    private String date;

    @NotEmpty(message = "At least one MSISDN is required")
    private List<@Pattern(regexp = "^\\+?\\d{7,15}$", message = "Invalid MSISDN format") String> msisdns;
}
