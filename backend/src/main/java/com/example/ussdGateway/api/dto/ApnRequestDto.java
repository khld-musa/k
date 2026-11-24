package com.example.ussdGateway.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApnRequestDto {

    private Long id;
    private String callId;
    private String msisdn;
    private String imsi;
    private String status;
    private String responseMessage;
    private Integer httpStatus;
    private Long latencyMs;
    private LocalDateTime createdAt;
}
