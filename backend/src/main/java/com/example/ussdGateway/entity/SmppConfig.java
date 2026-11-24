package com.example.ussdGateway.entity;

import com.example.ussdGateway.util.AttributeEncryptor;
import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "smpp_configs")
@Data
public class SmppConfig {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "smpp_host", nullable = false)
    private String smppHost;

    @Column(name = "smpp_port", nullable = false)
    private Integer smppPort = 2775;

    @Column(name = "system_id", nullable = false)
    private String systemId;

    @Convert(converter = AttributeEncryptor.class)
    @Column(name = "password", nullable = false)
    private String password;

    @Column(name = "system_type")
    private String systemType;

    @Column(name = "source_address")
    private String sourceAddress;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;
}
