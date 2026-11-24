package com.example.ussdGateway.service;

import com.example.ussdGateway.api.dto.SmppConfigDto;
import com.example.ussdGateway.entity.SmppConfig;
import com.example.ussdGateway.repository.SmppConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SmppConfigService {
    private final SmppConfigRepository repository;
    private final AuditLogService auditLogService;

    public List<SmppConfigDto> getAll() {
        return repository.findAll().stream().map(this::mapToDto).collect(Collectors.toList());
    }

    @Transactional
    public SmppConfigDto save(SmppConfigDto dto) {
        SmppConfig config;
        if (dto.getId() != null) {
            config = repository.findById(dto.getId()).orElse(new SmppConfig());
        } else {
            config = new SmppConfig();
        }
        config.setName(dto.getName());
        config.setSmppHost(dto.getSmppHost());
        config.setSmppPort(dto.getSmppPort() != null ? dto.getSmppPort() : 2775);
        config.setSystemId(dto.getSystemId());
        if (dto.getPassword() != null && !dto.getPassword().equals("********") && !dto.getPassword().isBlank()) {
            config.setPassword(dto.getPassword());
        }
        config.setSystemType(dto.getSystemType());
        config.setSourceAddress(dto.getSourceAddress());
        config.setIsActive(dto.getIsActive() != null ? dto.getIsActive() : true);
        
        boolean isNew = config.getId() == null;
        config = repository.save(config);
        auditLogService.logActivity(isNew ? "CREATE_SMPP" : "UPDATE_SMPP", 
                (isNew ? "Created" : "Updated") + " SMPP account: " + config.getName());
        return mapToDto(config);
    }

    @Transactional
    public void delete(Long id) {
        repository.findById(id).ifPresent(config -> {
            String name = config.getName();
            repository.deleteById(id);
            auditLogService.logActivity("DELETE_SMPP", "Deleted SMPP account: " + name);
        });
    }

    public void testConnection(SmppConfigDto dto) throws Exception {
        SmppConfig config = new SmppConfig();
        config.setSmppHost(dto.getSmppHost());
        config.setSmppPort(dto.getSmppPort() != null ? dto.getSmppPort() : 2775);
        config.setSystemId(dto.getSystemId());
        config.setPassword(dto.getPassword());
        config.setSystemType(dto.getSystemType());

        org.jsmpp.session.SMPPSession session = new org.jsmpp.session.SMPPSession();
        try {
            session.connectAndBind(
                    config.getSmppHost(),
                    config.getSmppPort(),
                    new org.jsmpp.session.BindParameter(
                            org.jsmpp.bean.BindType.BIND_TX,
                            config.getSystemId(),
                            config.getPassword(),
                            config.getSystemType() != null ? config.getSystemType() : "",
                            org.jsmpp.bean.TypeOfNumber.UNKNOWN,
                            org.jsmpp.bean.NumberingPlanIndicator.UNKNOWN,
                            null
                    )
            );
        } finally {
            session.unbindAndClose();
        }
        auditLogService.logActivity("TEST_SMPP", "Performed connection test for: " + dto.getName());
    }


    private SmppConfigDto mapToDto(SmppConfig config) {
        SmppConfigDto dto = new SmppConfigDto();
        dto.setId(config.getId());
        dto.setName(config.getName());
        dto.setSmppHost(config.getSmppHost());
        dto.setSmppPort(config.getSmppPort());
        dto.setSystemId(config.getSystemId());
        dto.setPassword("********");
        dto.setSystemType(config.getSystemType());
        dto.setSourceAddress(config.getSourceAddress());
        dto.setIsActive(config.getIsActive());
        return dto;
    }
}
