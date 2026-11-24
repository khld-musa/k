package com.example.ussdGateway.api.dto.response;

import com.example.ussdGateway.entity.SshSessionLog;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class SshSessionLogResponse {
    private Long id;
    private String username;
    private String targetHost;
    private String targetUser;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String status;
    private String errorMessage;

    public static SshSessionLogResponse fromEntity(SshSessionLog log) {
        return SshSessionLogResponse.builder()
                .id(log.getId())
                .username(log.getUsername())
                .targetHost(log.getTargetHost())
                .targetUser(log.getTargetUser())
                .startTime(log.getStartTime())
                .endTime(log.getEndTime())
                .status(log.getStatus())
                .errorMessage(log.getErrorMessage())
                .build();
    }
}
