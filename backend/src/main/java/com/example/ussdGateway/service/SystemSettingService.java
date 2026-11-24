package com.example.ussdGateway.service;

import com.example.ussdGateway.entity.SystemSetting;
import com.example.ussdGateway.repository.SystemSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class SystemSettingService {

    private final SystemSettingRepository systemSettingRepository;

    public String getSettingValue(String key, String defaultValue) {
        return systemSettingRepository.findBySettingKey(key)
                .map(SystemSetting::getSettingValue)
                .orElse(defaultValue);
    }

    @Transactional
    public void saveSettingValue(String key, String value, String description) {
        SystemSetting setting = systemSettingRepository.findBySettingKey(key)
                .orElse(new SystemSetting());
        
        setting.setSettingKey(key);
        setting.setSettingValue(value);
        if (description != null) {
            setting.setDescription(description);
        }
        
        systemSettingRepository.save(setting);
    }
}
