package com.example.ussdGateway.repository;

import com.example.ussdGateway.entity.AlarmHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AlarmHistoryRepository extends JpaRepository<AlarmHistory, Long> {

    // [FIX P2-16] Batch delete for old alarm history records
    @Modifying
    @Query(value = "DELETE FROM alarm_history WHERE id IN " +
                   "(SELECT id FROM alarm_history WHERE triggered_at < :cutoff LIMIT :batchSize)",
           nativeQuery = true)
    int deleteBatchByTriggeredAtBefore(@Param("cutoff") LocalDateTime cutoff,
                                       @Param("batchSize") int batchSize);

    long countByTriggeredAtBefore(LocalDateTime cutoff);

    /** Most recent N alarm history records — used for the dashboard "Recent Alarms" feed.
     *  Replaces the previous findAll() + in-Java sort + limit(3) pattern. */
    List<AlarmHistory> findTop3ByOrderByTriggeredAtDesc();

    /** Latest alarm history entry per alarm config — used for balance status display.
     *  Uses DISTINCT ON (PostgreSQL) to return only the most recent row per config,
     *  avoiding a full-table load followed by Java grouping logic. */
    @Query(value = "SELECT DISTINCT ON (alarm_config_id) * " +
                   "FROM alarm_history ORDER BY alarm_config_id, triggered_at DESC",
           nativeQuery = true)
    List<AlarmHistory> findLatestPerConfig();
}
