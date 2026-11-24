package com.example.ussdGateway.repository;

import com.example.ussdGateway.entity.BalanceRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BalanceRecordRepository extends JpaRepository<BalanceRecord, Long> {

    /**
     * Find the current (latest) balance record for a specific alarm config.
     * Used for updating the single record instead of creating duplicates.
     */
    Optional<BalanceRecord> findFirstByAlarmConfigIdOrderByIdDesc(Long alarmConfigId);
}

