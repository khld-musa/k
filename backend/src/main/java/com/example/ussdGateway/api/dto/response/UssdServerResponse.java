package com.example.ussdGateway.api.dto.response;

import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class UssdServerResponse {
    private Long id;
    private String name;
    private String ipAddress;
    private String description;
    private String logPath;
    private String localLogPath;
    private String sshUsername;
    private boolean hasPasswordStored;
    private String ftpUsername;
    private boolean hasFtpPasswordStored;
    private String ftpRemotePath;
    private LocalDate lastFetchDate;
    private boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
