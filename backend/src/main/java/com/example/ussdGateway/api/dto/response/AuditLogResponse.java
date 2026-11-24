package com.example.ussdGateway.api.dto.response;

import com.example.ussdGateway.entity.AuditLog;

import java.time.LocalDateTime;

public class AuditLogResponse {

    private Long id;
    private String action;
    private String username;
    private String targetUser;
    private String details;
    private String ipAddress;
    private LocalDateTime timestamp;

    public AuditLogResponse(AuditLog entity) {
        this.id = entity.getId();
        this.action = entity.getAction();
        this.username = entity.getUsername();
        this.targetUser = entity.getTargetUser();
        this.details = entity.getDetails();
        this.ipAddress = entity.getIpAddress();
        this.timestamp = entity.getTimestamp();
    }

    // Getters
    public Long getId() { return id; }
    public String getAction() { return action; }
    public String getUsername() { return username; }
    public String getTargetUser() { return targetUser; }
    public String getDetails() { return details; }
    public String getIpAddress() { return ipAddress; }
    public LocalDateTime getTimestamp() { return timestamp; }
}
