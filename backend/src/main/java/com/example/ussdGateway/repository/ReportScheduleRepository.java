package com.example.ussdGateway.repository;

import com.example.ussdGateway.entity.ReportSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ReportScheduleRepository extends JpaRepository<ReportSchedule, Long> {

    List<ReportSchedule> findByIsActiveTrue();

    /** Find all active schedules that are due for execution (nextRunAt <= now). */
    List<ReportSchedule> findByIsActiveTrueAndNextRunAtBefore(LocalDateTime now);
}

