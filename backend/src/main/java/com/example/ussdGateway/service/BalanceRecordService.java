package com.example.ussdGateway.service;

import com.example.ussdGateway.api.dto.BalanceRecordDto;
import com.example.ussdGateway.entity.BalanceRecord;
import com.example.ussdGateway.repository.BalanceRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for managing balance status records.
 * Provides operations to query the current balance status for alarm configurations.
 * Note: Each alarm config has only one record that is updated on each check.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BalanceRecordService {

    private final BalanceRecordRepository balanceRecordRepository;

    /**
     * Get the current balance record for a specific alarm config.
     */
    @Transactional(readOnly = true)
    public BalanceRecordDto getRecordByAlarmConfigId(Long alarmConfigId) {
        return balanceRecordRepository.findFirstByAlarmConfigIdOrderByIdDesc(alarmConfigId)
                .map(this::mapToDto)
                .orElse(null);
    }

    /**
     * Get all balance records (one per alarm config).
     */
    @Transactional(readOnly = true)
    public List<BalanceRecordDto> getAllRecords() {
        return balanceRecordRepository.findAll()
                .stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    /**
     * Get balance records for multiple alarm configs.
     */
    @Transactional(readOnly = true)
    public List<BalanceRecordDto> getRecordsByAlarmConfigIds(List<Long> alarmConfigIds) {
        return balanceRecordRepository.findAllById(alarmConfigIds)
                .stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }


    /**
     * Map BalanceRecord entity to DTO.
     */
    private BalanceRecordDto mapToDto(BalanceRecord record) {
        BalanceRecordDto dto = new BalanceRecordDto();
        dto.setId(record.getId());
        dto.setAlarmConfigId(record.getAlarmConfigId());
        dto.setUsername(record.getUsername());
        dto.setClientId(record.getClientId());
        dto.setBalance(record.getBalance());
        dto.setThreshold(record.getThreshold());
        dto.setCheckedAt(record.getCheckedAt());
        dto.setIsBelowThreshold(record.getIsBelowThreshold());
        dto.setAlertTriggered(record.getAlertTriggered());
        return dto;
    }
}

