package com.example.ussdGateway.api.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LogEvent {
    private String timestamp;
    private String eventType; // e.g. "USSD ENTRY", "HTTP REQUEST", "HTTP RESPONSE", "CDR", "USSD RESPONSE"
    private String details;   // The core snippet describing this event
    private String payload;   // The extracted JSON payload or structured data
    private String rawLine;   // The full line for reference
}
