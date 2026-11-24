package com.example.ussdGateway.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McnCheckerRequest {

    @NotBlank(message = "MSISDN is required")
    @Pattern(regexp = "^\\+?\\d{7,15}$", message = "Invalid MSISDN format (7-15 digits)")
    private String msisdn;

    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "Date must be in yyyy-MM-dd format")
    private String date;

    private String username;

    private String password;

    private Boolean useStoredCredentials;
}
