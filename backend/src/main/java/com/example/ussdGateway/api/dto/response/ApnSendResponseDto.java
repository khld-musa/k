package com.example.ussdGateway.api.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApnSendResponseDto {

    // USSD Gateway specific protocol fields
    private Integer responseCode;
    private String transactionId;
    private String callID;

    @JsonProperty("statusDetails_status")
    private String statusDetailsStatus;

    @JsonProperty("statusDetails_code")
    private String statusDetailsCode;

    private String result;
    private String msisdn;
    private String responseMessage;

    // Standard API fields
    private boolean success;
    private String message;
    private Object data;
}
