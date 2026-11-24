package com.example.ussdGateway.api.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LiveLogUpdate {
    private String serverId;
    private String serverIp;
    private String callId;
    private String msisdn;
    private String shortCode;
    private String callSource;
    private LogEvent event;
}
