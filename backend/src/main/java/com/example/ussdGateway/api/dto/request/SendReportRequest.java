package com.example.ussdGateway.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.List;

/**
 * Request body for the manual "Send Now" report email endpoint.
 */
@Data
public class SendReportRequest {

    @NotBlank(message = "Start date is required")
    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "Date must be in yyyy-MM-dd format")
    private String startDate;

    @NotBlank(message = "End date is required")
    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "Date must be in yyyy-MM-dd format")
    private String endDate;

    /** Optional node IP filter; null/empty = ALL nodes */
    private String nodeIp;

    @NotEmpty(message = "At least one contact group must be selected")
    private List<Long> contactGroupIds;
}

