package com.example.ussdGateway.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.Map;

@Data
public class ReportRequest {
    @NotBlank(message = "Start date is required (e.g., YYYY-MM-DD)")
    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "Date must be in yyyy-MM-dd format")
    private String startDate;

    @NotBlank(message = "End date is required (e.g., YYYY-MM-DD)")
    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "Date must be in yyyy-MM-dd format")
    private String endDate;

    // [FIX R-1] Removed unused username/password fields — reports are database-only

    private Map<String, String> nodes;

    // [FIX P2-12] Optional: filter all metrics to a single node IP.
    // When set, the cache key and all queries are scoped to that node only.
    private String nodeIp;
}
