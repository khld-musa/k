package com.example.ussdGateway.entity;

import com.example.ussdGateway.util.AttributeEncryptor;
import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "email_configs")
@Data
public class EmailConfig {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "smtp_host", nullable = false)
    private String smtpHost;

    @Column(name = "smtp_port", nullable = false)
    private Integer smtpPort = 587;

    @Column(name = "username", nullable = false)
    private String username;

    @Convert(converter = AttributeEncryptor.class)
    @Column(name = "password", nullable = false)
    private String password;

    @Column(name = "from_address", nullable = false)
    private String fromAddress;

    @Column(name = "use_tls", nullable = false)
    private Boolean useTls = true;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;
}
