package com.example.ussdGateway.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "report_jobs", indexes = {
    @Index(name = "idx_report_jobs_status", columnList = "status"),
    @Index(name = "idx_report_jobs_user", columnList = "requested_by, requested_at"),
    @Index(name = "idx_report_jobs_expires", columnList = "expires_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "report_type", nullable = false, length = 50)
    private String reportType;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "filters_json", columnDefinition = "TEXT")
    private String filtersJson;

    @Column(name = "file_path", length = 500)
    private String filePath;

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "requested_by", length = 100)
    private String requestedBy;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "downloaded_at")
    private LocalDateTime downloadedAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "rows_written")
    private Long rowsWritten;

    @Column(name = "total_rows")
    private Long totalRows;

    @Column(name = "heartbeat_at")
    private LocalDateTime heartbeatAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (requestedAt == null) {
            requestedAt = LocalDateTime.now();
        }
        if (status == null) {
            status = "QUEUED";
        }
    }
}
