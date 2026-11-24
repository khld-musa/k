package com.example.ussdGateway.service;

import com.example.ussdGateway.api.dto.AlarmConfigDto;
import com.example.ussdGateway.api.dto.AlarmHistoryDto;
import com.example.ussdGateway.entity.AlarmConfig;
import com.example.ussdGateway.entity.AlarmHistory;
import com.example.ussdGateway.entity.ContactGroup;
import com.example.ussdGateway.repository.AlarmConfigRepository;
import com.example.ussdGateway.repository.AlarmHistoryRepository;
import com.example.ussdGateway.repository.ContactGroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AlarmConfigService {
    private final AlarmConfigRepository alarmConfigRepository;
    private final AlarmHistoryRepository alarmHistoryRepository;
    private final ContactGroupRepository contactGroupRepository;
    private final AuditLogService auditLogService;

    /** Maximum number of history records returned to avoid OOM on large tables */
    private static final int MAX_HISTORY_RECORDS = 200;

    public List<AlarmConfigDto> getAllConfigs() {
        return alarmConfigRepository.findAll().stream().map(this::mapToDto).collect(Collectors.toList());
    }

    /** Returns true if an AlarmConfig with the given ID exists — used by the controller for 404 checks */
    public boolean existsById(Long id) {
        return alarmConfigRepository.existsById(id);
    }

    @Transactional
    public AlarmConfigDto saveConfig(AlarmConfigDto dto) {
        AlarmConfig config;
        if (dto.getId() != null) {
            // [FIX] Return 404 when ID is provided but not found, instead of silently creating a new record
            config = alarmConfigRepository.findById(dto.getId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "Alarm config not found with id: " + dto.getId()));
        } else {
            config = new AlarmConfig();
        }

        config.setUsername(dto.getUsername());
        config.setClientId(dto.getClientId());

        // Only update API key if provided (don't overwrite with masked string)
        if (dto.getApiKey() != null && !dto.getApiKey().equals("********") && !dto.getApiKey().isBlank()) {
            config.setApiKey(dto.getApiKey());
        }

        config.setMinimumThreshold(dto.getMinimumThreshold());
        config.setIsActive(dto.getIsActive() != null ? dto.getIsActive() : true);
        config.setCheckIntervalMinutes(dto.getCheckIntervalMinutes() != null ? dto.getCheckIntervalMinutes() : 30);
        config.setCooldownMinutes(dto.getCooldownMinutes() != null ? dto.getCooldownMinutes() : 360);

        Set<ContactGroup> subscribedGroups = new HashSet<>();
        if (dto.getSubscribedGroupIds() != null) {
            subscribedGroups.addAll(contactGroupRepository.findAllById(dto.getSubscribedGroupIds()));
        }
        config.setSubscribedGroups(subscribedGroups);

        boolean isNew = config.getId() == null;
        config = alarmConfigRepository.save(config);
        auditLogService.logActivity(isNew ? "CREATE_ALARM" : "UPDATE_ALARM",
                (isNew ? "Created" : "Updated") + " alarm for user: " + config.getUsername());
        return mapToDto(config);
    }

    @Transactional
    public void deleteConfig(Long id) {
        alarmConfigRepository.findById(id).ifPresent(config -> {
            String username = config.getUsername();
            alarmConfigRepository.deleteById(id);
            auditLogService.logActivity("DELETE_ALARM", "Deleted alarm for user: " + username);
        });
    }

    @Transactional
    public void resetCooldown(Long id) {
        AlarmConfig config = alarmConfigRepository.findById(id).orElseThrow();
        config.setLastAlertSentAt(null);
        alarmConfigRepository.saveAndFlush(config);
        auditLogService.logActivity("RESET_COOLDOWN", "Reset alert cooldown for: " + config.getUsername());
    }

    /**
     * Returns the most recent alarm history records, limited to MAX_HISTORY_RECORDS.
     * [FIX PERF-3] Replaces findAll() + in-memory sort which would OOM on large tables.
     */
    public List<AlarmHistoryDto> getAllHistory() {
        return alarmHistoryRepository.findAll(
                PageRequest.of(0, MAX_HISTORY_RECORDS, Sort.by(Sort.Direction.DESC, "triggeredAt"))
        ).getContent().stream()
                .map(this::mapToHistoryDto)
                .collect(Collectors.toList());
    }

    private AlarmHistoryDto mapToHistoryDto(AlarmHistory history) {
        AlarmHistoryDto dto = new AlarmHistoryDto();
        dto.setId(history.getId());
        dto.setAlarmConfigId(history.getAlarmConfigId());
        dto.setUsername(history.getUsername());
        dto.setClientId(history.getClientId());
        dto.setCurrentBalance(history.getCurrentBalance());
        dto.setThreshold(history.getThreshold());
        dto.setTriggeredAt(history.getTriggeredAt());
        dto.setSmsMessage(history.getSmsMessage());
        dto.setEmailMessage(history.getEmailMessage());
        return dto;
    }


    private AlarmConfigDto mapToDto(AlarmConfig config) {
        AlarmConfigDto dto = new AlarmConfigDto();
        dto.setId(config.getId());
        dto.setUsername(config.getUsername());
        dto.setClientId(config.getClientId());
        dto.setApiKey("********"); // Always mask the API key in the standard DTO mapping
        dto.setMinimumThreshold(config.getMinimumThreshold());
        dto.setIsActive(config.getIsActive());
        dto.setCheckIntervalMinutes(config.getCheckIntervalMinutes());
        dto.setCooldownMinutes(config.getCooldownMinutes());

        if (config.getSubscribedGroups() != null) {
            dto.setSubscribedGroupIds(config.getSubscribedGroups().stream().map(ContactGroup::getId).collect(Collectors.toSet()));
        }
        return dto;
    }
}
