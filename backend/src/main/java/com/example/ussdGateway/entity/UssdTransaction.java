package com.example.ussdGateway.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Pre-analytics storage for high-speed USSD log reporting.
 * One row per USSD call (keyed by the unique TIBCO callID), populated by
 * {@link com.example.ussdGateway.service.LogIngestionService} from the daily USSD log files.
 */
@Entity
@Table(name = "ussd_transactions")
@Data
@NoArgsConstructor
public class UssdTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "ussd_tx_seq")
    @SequenceGenerator(name = "ussd_tx_seq", sequenceName = "ussd_transactions_id_seq", allocationSize = 50)
    private Long id;

    @Column(name = "call_id", nullable = false, length = 50)
    private String callId;

    @Column(name = "msisdn", length = 20)
    private String msisdn;

    @Column(name = "transaction_id", length = 100)
    private String transactionId;

    @Column(name = "status", length = 20)
    private String status;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "short_code", length = 100)
    private String shortCode;

    @Column(name = "product_name", length = 255)
    private String productName;

    @Column(name = "error_code", length = 100)
    private String errorCode;

    @Column(name = "service_type", length = 50)
    private String serviceType;

    @Column(name = "lang", length = 10)
    private String lang;

    @Column(name = "node_ip", length = 50)
    private String nodeIp;

    @Column(name = "cell_id", length = 50)
    private String cellId;

    @Column(name = "vlr", length = 50)
    private String vlr;

    @Column(name = "channel", length = 50)
    private String channel;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "tibco_response", columnDefinition = "TEXT")
    private String tibcoResponse;

    @Column(name = "error_reason", columnDefinition = "TEXT")
    private String errorReason;

    @Column(name = "remote_url", length = 500)
    private String remoteUrl;

    @Column(name = "subscriber_reply", columnDefinition = "TEXT")
    private String subscriberReply;

    @Column(name = "response_time")
    private LocalDateTime responseTime;

    @Column(name = "triggered_at", nullable = false)
    private LocalDateTime triggeredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    @PreUpdate
    protected void onSave() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        truncateFields();
    }

    public void truncateFields() {
        this.callId = truncate(this.callId, 50);
        this.msisdn = truncate(this.msisdn, 20);
        this.transactionId = truncate(this.transactionId, 100);
        this.status = truncate(this.status, 20);
        this.shortCode = truncate(this.shortCode, 100);
        this.productName = truncate(this.productName, 255);
        this.errorCode = truncate(this.errorCode, 100);
        this.serviceType = truncate(this.serviceType, 50);
        this.lang = truncate(this.lang, 10);
        this.nodeIp = truncate(this.nodeIp, 50);
        this.cellId = truncate(this.cellId, 50);
        this.vlr = truncate(this.vlr, 50);
        this.channel = truncate(this.channel, 50);
        this.remoteUrl = truncate(this.remoteUrl, 500);
        this.errorMessage = truncate(this.errorMessage, 500);
        this.tibcoResponse = truncate(this.tibcoResponse, 500);
        this.errorReason = truncate(this.errorReason, 500);
        this.subscriberReply = truncate(this.subscriberReply, 500);
    }

    private static String truncate(String val, int maxLen) {
        if (val == null || val.length() <= maxLen) return val;
        return val.substring(0, maxLen);
    }
}
