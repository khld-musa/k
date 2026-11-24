package com.example.ussdGateway.api.dto.request;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ApnSendRequestDto {

    @JsonAlias({"IMSI", "Imsi"})
    private String imsi;

    @JsonAlias({"MSISDN", "Msisdn", "phone", "phoneNumber"})
    private String msisdn;

    @JsonAlias({"callId", "callID", "CallID"})
    private String callId;

    @JsonAlias({"serviceType", "ServiceType"})
    private String serviceType;
}
