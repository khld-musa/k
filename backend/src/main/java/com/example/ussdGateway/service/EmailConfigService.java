package com.example.ussdGateway.service;

import com.example.ussdGateway.api.dto.EmailConfigDto;
import com.example.ussdGateway.entity.EmailConfig;
import com.example.ussdGateway.repository.EmailConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EmailConfigService {
    private final EmailConfigRepository repository;
    private final AuditLogService auditLogService;

    public List<EmailConfigDto> getAll() {
        return repository.findAll().stream().map(this::mapToDto).collect(Collectors.toList());
    }

    @Transactional
    public EmailConfigDto save(EmailConfigDto dto) {
        EmailConfig config;
        if (dto.getId() != null) {
            config = repository.findById(dto.getId()).orElse(new EmailConfig());
        } else {
            config = new EmailConfig();
        }
        config.setName(dto.getName());
        config.setSmtpHost(dto.getSmtpHost());
        config.setSmtpPort(dto.getSmtpPort() != null ? dto.getSmtpPort() : 587);
        config.setUsername(dto.getUsername());
        if (dto.getPassword() != null && !dto.getPassword().equals("********") && !dto.getPassword().isBlank()) {
            config.setPassword(dto.getPassword());
        }
        config.setFromAddress(dto.getFromAddress());
        config.setUseTls(dto.getUseTls() != null ? dto.getUseTls() : true);
        config.setIsActive(dto.getIsActive() != null ? dto.getIsActive() : true);

        boolean isNew = config.getId() == null;
        config = repository.save(config);
        auditLogService.logActivity(isNew ? "CREATE_EMAIL" : "UPDATE_EMAIL", 
                (isNew ? "Created" : "Updated") + " mail server: " + config.getName());
        return mapToDto(config);
    }

    @Transactional
    public void delete(Long id) {
        repository.findById(id).ifPresent(config -> {
            String name = config.getName();
            repository.deleteById(id);
            auditLogService.logActivity("DELETE_EMAIL", "Deleted mail server: " + name);
        });
    }

    public void testConnection(EmailConfigDto dto) throws Exception {
        EmailConfig config = new EmailConfig();
        config.setSmtpHost(dto.getSmtpHost());
        config.setSmtpPort(dto.getSmtpPort() != null ? dto.getSmtpPort() : 587);
        config.setUsername(dto.getUsername());
        config.setPassword(dto.getPassword());
        config.setFromAddress(dto.getFromAddress());
        config.setUseTls(dto.getUseTls() != null ? dto.getUseTls() : true);
        
        // Build sender and send a test mail
        org.springframework.mail.javamail.JavaMailSenderImpl mailSender = new org.springframework.mail.javamail.JavaMailSenderImpl();
        mailSender.setHost(config.getSmtpHost());
        mailSender.setPort(config.getSmtpPort());
        mailSender.setUsername(config.getUsername());
        mailSender.setPassword(config.getPassword());

        java.util.Properties props = mailSender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", String.valueOf(config.getUseTls()));
        
        mailSender.testConnection();
        auditLogService.logActivity("TEST_EMAIL", "Performed connection test for mail server: " + dto.getName());
    }


    private EmailConfigDto mapToDto(EmailConfig config) {
        EmailConfigDto dto = new EmailConfigDto();
        dto.setId(config.getId());
        dto.setName(config.getName());
        dto.setSmtpHost(config.getSmtpHost());
        dto.setSmtpPort(config.getSmtpPort());
        dto.setUsername(config.getUsername());
        dto.setPassword("********");
        dto.setFromAddress(config.getFromAddress());
        dto.setUseTls(config.getUseTls());
        dto.setIsActive(config.getIsActive());
        return dto;
    }
}
