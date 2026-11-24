package com.example.ussdGateway.api.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SavedSshSessionRequest {
    @NotBlank(message = "Session name is required")
    @Size(min = 1, max = 100, message = "Session name must be between 1 and 100 characters")
    private String name;

    @NotBlank(message = "Host is required")
    @Size(max = 255, message = "Host must be less than 255 characters")
    // Simple regex for hostname/IP validation could be added, but might be too restrictive
    private String host;

    @Min(value = 1, message = "Port must be greater than 0")
    @Max(value = 65535, message = "Port must be less than or equal to 65535")
    private Integer port;

    @NotBlank(message = "Username is required")
    @Size(max = 100, message = "Username must be less than 100 characters")
    private String username;

    private String password;
    private String privateKey;
}
