package com.example.ussdGateway.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UssdServerRequest {
    @NotBlank(message = "Name is required")
    private String name;

    @NotBlank(message = "IP Address is required")
    private String ipAddress;

    private String description;

    private String logPath;

    private String localLogPath;

    private String sshUsername;

    private String sshPassword;

    private String ftpUsername;

    private String ftpPassword;

    private String ftpRemotePath;

    private boolean active = true;
}
