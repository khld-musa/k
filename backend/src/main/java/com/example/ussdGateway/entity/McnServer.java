package com.example.ussdGateway.entity;

import com.example.ussdGateway.util.AttributeEncryptor;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "mcn_servers")
@Data
@NoArgsConstructor
public class McnServer {

    public enum ServerType {
        SIP,
        NOTIFIER
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 50)
    private String ipAddress;

    @Column(length = 255)
    private String description;

    @Column(name = "log_path", length = 500)
    private String logPath;

    @Column(nullable = false)
    private boolean active = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "server_type", nullable = false, length = 20)
    private ServerType serverType;

    @Column(name = "ssh_username", length = 100)
    private String sshUsername;

    @Convert(converter = AttributeEncryptor.class)
    @Column(name = "encrypted_password", length = 500)
    private String sshPassword;

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
