package com.example.ussdGateway.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Request DTO for starting a live trace session.
 * Credentials are sent in the POST body (not URL params).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LiveTraceRequest {

    @NotEmpty(message = "At least one server node is required")
    private Map<String, String> nodes; // serverId → serverIp

    private String username;

    private String password;

    @NotEmpty(message = "At least one MSISDN is required")
    private List<String> msisdns;
}

