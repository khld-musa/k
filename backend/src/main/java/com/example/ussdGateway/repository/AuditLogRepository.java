package com.example.ussdGateway.repository;

import com.example.ussdGateway.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    List<AuditLog> findAllByOrderByTimestampDesc();
    Page<AuditLog> findAllByOrderByTimestampDesc(Pageable pageable);
    Page<AuditLog> findByUsernameOrderByTimestampDesc(String username, Pageable pageable);
    Page<AuditLog> findByUsernameAndActionInOrderByTimestampDesc(String username, List<String> actions, Pageable pageable);

    /** Count audit log entries by action since a given timestamp — used by dashboard instead of
     *  fetching 1000 rows into Java and filtering in-process. */
    @Query("SELECT COUNT(a) FROM AuditLog a WHERE a.action = :action AND a.timestamp >= :since")
    long countByActionSince(@Param("action") String action, @Param("since") LocalDateTime since);

    /** Count entries by action within an inclusive time window — used for the 7-day activity chart. */
    @Query("SELECT COUNT(a) FROM AuditLog a WHERE a.action = :action AND a.timestamp BETWEEN :from AND :to")
    long countByActionBetween(@Param("action") String action,
                              @Param("from") LocalDateTime from,
                              @Param("to") LocalDateTime to);

    /** Recent activity feed: top N audit entries matching the given actions, ordered newest-first.
     *  Runs entirely in SQL instead of pulling 1000 rows and streaming in Java. */
    @Query("SELECT a FROM AuditLog a WHERE a.action IN :actions ORDER BY a.timestamp DESC")
    Page<AuditLog> findRecentByActions(@Param("actions") List<String> actions, Pageable pageable);
}
