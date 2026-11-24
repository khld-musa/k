package com.example.ussdGateway.api.dto.response;

import com.example.ussdGateway.entity.McnServer;
import lombok.Data;
import java.time.LocalDateTime;

@Data
public class McnServerResponse {
    private Long id;
    private String name;
    private String ipAddress;
    private String description;
    private String logPath;
    private McnServer.ServerType serverType;
    private String sshUsername;
    private boolean hasPasswordStored;
    private boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
