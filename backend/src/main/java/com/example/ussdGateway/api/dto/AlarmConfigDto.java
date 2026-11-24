package com.example.ussdGateway.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.math.BigDecimal;
import java.util.Set;

@Data
public class AlarmConfigDto {
    private Long id;

    @NotBlank(message = "Username/label is required")
    @Size(max = 255, message = "Username must not exceed 255 characters")
    private String username;

    @NotBlank(message = "Client ID is required")
    @Size(max = 255, message = "Client ID must not exceed 255 characters")
    private String clientId;

    private String apiKey; // Optional on update — backend ignores "********"

    @NotNull(message = "Minimum threshold is required")
    @DecimalMin(value = "0.0", message = "Minimum threshold must be 0 or greater")
    private BigDecimal minimumThreshold;

    /** Per-alarm check interval in minutes (1–1440). Defaults to 30 if not provided. */
    @Min(value = 1, message = "Check interval must be at least 1 minute")
    @Max(value = 1440, message = "Check interval must not exceed 1440 minutes (24 hours)")
    private Integer checkIntervalMinutes;

    /** Per-alarm alert cooldown in minutes (1–1440). Defaults to 360 (6h) if not provided. */
    @Min(value = 1, message = "Cooldown must be at least 1 minute")
    @Max(value = 1440, message = "Cooldown must not exceed 1440 minutes (24 hours)")
    private Integer cooldownMinutes;

    private Boolean isActive;
    private Set<Long> subscribedGroupIds;
}
