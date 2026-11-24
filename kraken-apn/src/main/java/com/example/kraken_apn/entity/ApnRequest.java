package com.example.kraken_apn.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "apn_requests", indexes = {
    @Index(name = "idx_apn_requests_created", columnList = "created_at"),
    @Index(name = "idx_apn_requests_msisdn", columnList = "msisdn"),
    @Index(name = "idx_apn_requests_status", columnList = "status"),
    @Index(name = "idx_apn_requests_created_status", columnList = "created_at, status")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApnRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "call_id", length = 100)
    private String callId;

    @Column(name = "msisdn", nullable = false, length = 30)
    private String msisdn;

    @Column(name = "imsi", nullable = false, length = 30)
    private String imsi;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "response_message", length = 500)
    private String responseMessage;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "raw_request", columnDefinition = "TEXT")
    private String rawRequest;

    @Column(name = "raw_response", columnDefinition = "TEXT")
    private String rawResponse;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
