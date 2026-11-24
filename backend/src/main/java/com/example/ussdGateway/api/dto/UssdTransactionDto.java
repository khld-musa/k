package com.example.ussdGateway.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * DTO for UssdTransaction - a single reported USSD call record.
 */
@Data
public class UssdTransactionDto {

    private Long id;
    private String callId;
    private String msisdn;
    private String transactionId;
    private String status;
    private Long latencyMs;
    private String shortCode;
    private String productName;
    private String errorCode;
    private String errorMessage;
    private String serviceType;
    private String lang;
    private String nodeIp;
    private String cellId;
    private String vlr;
    private String channel;
    private String remoteUrl;
    private String subscriberReply;
    private String tibcoResponse;
    private String errorReason;
    private LocalDateTime responseTime;
    private LocalDateTime triggeredAt;
}
