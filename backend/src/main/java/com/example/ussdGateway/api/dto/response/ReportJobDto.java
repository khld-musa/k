package com.example.ussdGateway.api.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportJobDto {

    private UUID id;
    private String reportType;
    private String status;
    private String fileName;
    private Long fileSize;
    private String requestedBy;
    private LocalDateTime requestedAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime downloadedAt;
    private String errorMessage;
    private Long rowsWritten;
    private Long totalRows;
    private String downloadUrl;
}
