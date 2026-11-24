package com.example.ussdGateway.api.dto;

import jakarta.validation.constraints.*;

import lombok.Data;

@Data
public class SmppConfigDto {
    private Long id;
    @NotBlank(message = "Name is required")
    @Size(max = 255, message = "Name must not exceed 255 characters")
    private String name;
    @NotBlank(message = "SMPP host is required")
    @Size(max = 255, message = "SMPP host must not exceed 255 characters")
    private String smppHost;
    @NotNull(message = "Port is required")
    @Min(value = 1, message = "Port must be 1-65535")
    @Max(value = 65535, message = "Port must be 1-65535")
    private Integer smppPort;
    @NotBlank(message = "System ID is required")
    @Size(max = 16, message = "System ID must not exceed 16 characters")
    private String systemId;
    private String password;
    @Size(max = 13, message = "System type must not exceed 13 characters")
    private String systemType;
    @Size(max = 21, message = "Source address must not exceed 21 characters")
    private String sourceAddress;
    private Boolean isActive;
}
