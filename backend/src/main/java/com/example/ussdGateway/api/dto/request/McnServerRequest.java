package com.example.ussdGateway.api.dto.request;

import com.example.ussdGateway.entity.McnServer;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class McnServerRequest {
    @NotBlank(message = "Name is required")
    private String name;

    @NotBlank(message = "IP Address is required")
    private String ipAddress;

    private String description;

    private String logPath;

    @NotNull(message = "Server Type (SIP or NOTIFIER) is required")
    private McnServer.ServerType serverType;

    private String sshUsername;

    private String sshPassword;
    
    private boolean active = true;
}
