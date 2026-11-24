package com.example.ussdGateway.api.response;

import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;
import java.util.ArrayList;

@Data
@NoArgsConstructor
public class UssdLogGroup {
    private String serverId;
    private String serverIp;
    private String callId;
    private String msisdn;
    private String shortCode;
    private String callSource;
    private String startTime;
    private String status;
    private String resultMessage;
    
    // Deeper parsing lists
    private List<LogEvent> events = new ArrayList<>();
    
    // The raw string array for backward compatibility
    private List<String> logs = new ArrayList<>();

    public UssdLogGroup(String callId) {
        this.callId = callId;
    }

    public UssdLogGroup(String serverId, String serverIp, String callId) {
        this.serverId = serverId;
        this.serverIp = serverIp;
        this.callId = callId;
    }
}
