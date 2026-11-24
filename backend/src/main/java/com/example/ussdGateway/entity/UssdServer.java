package com.example.ussdGateway.entity;

import com.example.ussdGateway.util.AttributeEncryptor;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "ussd_servers")
@Data
@NoArgsConstructor
public class UssdServer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 50)
    private String ipAddress;

    @Column(length = 255)
    private String description;

    /** Remote directory on the node itself, used by {@code SshLogService}'s live SSH log search. */
    @Column(name = "log_path", length = 500)
    private String logPath;

    /**
     * Local-disk base directory for USSD log files pulled in by {@code LogFetchService} and read
     * by {@code LogIngestionService} - independent of {@link #logPath} above. Each server's files
     * live under {@code {localLogPath}/{name}/}, see {@code ServerLogPaths}.
     */
    @Column(name = "local_log_path", nullable = false, length = 500)
    private String localLogPath = "/var/log/ussd";

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "ssh_username", length = 100)
    private String sshUsername;

    @Convert(converter = AttributeEncryptor.class)
    @Column(name = "encrypted_password", length = 500)
    private String sshPassword;

    @Column(name = "last_read_offset")
    private Long lastReadOffset = 0L;

    @Column(name = "last_sync_date")
    private LocalDate lastSyncDate;

    /** Plain-FTP credentials/path used by {@code LogFetchService} to pull daily log files from this node. */
    @Column(name = "ftp_username", length = 100)
    private String ftpUsername;

    @Convert(converter = AttributeEncryptor.class)
    @Column(name = "encrypted_ftp_password", length = 500)
    private String ftpPassword;

    @Column(name = "ftp_remote_path", nullable = false, length = 500)
    private String ftpRemotePath = "/mediation";

    /** Last calendar day whose log file was fully pulled from the remote FTP node (and deleted there). */
    @Column(name = "last_fetch_date")
    private LocalDate lastFetchDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
