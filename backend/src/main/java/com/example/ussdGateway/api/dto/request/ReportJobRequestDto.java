package com.example.ussdGateway.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportJobRequestDto {

    @NotBlank(message = "Report type is required")
    private String reportType;

    /** Map of filter keys and values applied in the UI */
    private Map<String, String> filters;
}
