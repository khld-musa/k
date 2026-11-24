package com.example.ussdGateway.api.dto;

import jakarta.validation.constraints.*;

import lombok.Data;

@Data
public class EmailConfigDto {
    private Long id;
    @NotBlank(message = "Name is required")
    @Size(max = 255, message = "Name must not exceed 255 characters")
    private String name;
    @NotBlank(message = "SMTP host is required")
    @Size(max = 255, message = "SMTP host must not exceed 255 characters")
    private String smtpHost;
    @NotNull(message = "Port is required")
    @Min(value = 1, message = "Port must be 1-65535")
    @Max(value = 65535, message = "Port must be 1-65535")
    private Integer smtpPort;
    @NotBlank(message = "Username is required")
    @Size(max = 255, message = "Username must not exceed 255 characters")
    private String username;
    private String password;
    @NotBlank(message = "From address is required")
    @Email(message = "Invalid email format")
    private String fromAddress;
    private Boolean useTls;
    private Boolean isActive;
}
