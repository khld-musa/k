package com.example.ussdGateway.repository;

import com.example.ussdGateway.entity.ReportJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReportJobRepository extends JpaRepository<ReportJob, UUID> {

    List<ReportJob> findTop10ByRequestedByOrderByRequestedAtDesc(String requestedBy);

    Optional<ReportJob> findFirstByRequestedByAndReportTypeOrderByRequestedAtDesc(String requestedBy, String reportType);

    Optional<ReportJob> findFirstByRequestedByAndReportTypeAndStatusInOrderByRequestedAtDesc(
            String requestedBy, String reportType, Collection<String> statuses);

    List<ReportJob> findAllByExpiresAtBefore(LocalDateTime cutoff);

    List<ReportJob> findAllByStatusInAndStartedAtBefore(Collection<String> statuses, LocalDateTime cutoff);

    long countByRequestedByAndStatusIn(String requestedBy, Collection<String> statuses);

    List<ReportJob> findAllByStatusIn(Collection<String> statuses);

    Optional<ReportJob> findFirstByRequestedByAndReportTypeAndFiltersJsonAndStatusInOrderByRequestedAtDesc(
            String requestedBy, String reportType, String filtersJson, Collection<String> statuses);

    @Query("SELECT j FROM ReportJob j WHERE j.status = 'PROCESSING' "
            + "AND (COALESCE(j.heartbeatAt, j.startedAt) < :staleCutoff OR j.startedAt < :hardCutoff)")
    List<ReportJob> findStuckProcessing(@Param("staleCutoff") LocalDateTime staleCutoff,
                                        @Param("hardCutoff") LocalDateTime hardCutoff);

    // Conditional state transitions: a late worker can never overwrite a cancelled/timed-out job.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE ReportJob j SET j.status = 'PROCESSING', j.startedAt = :now, j.heartbeatAt = :now "
            + "WHERE j.id = :id AND j.status = 'QUEUED'")
    int markProcessing(@Param("id") UUID id, @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE ReportJob j SET j.rowsWritten = :rows, j.totalRows = :total, j.heartbeatAt = :now "
            + "WHERE j.id = :id AND j.status = 'PROCESSING'")
    int updateProgress(@Param("id") UUID id, @Param("rows") long rows, @Param("total") long total,
                       @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE ReportJob j SET j.status = 'COMPLETED', j.filePath = :path, j.fileSize = :size, "
            + "j.rowsWritten = :rows, j.completedAt = :now "
            + "WHERE j.id = :id AND j.status = 'PROCESSING'")
    int markCompleted(@Param("id") UUID id, @Param("path") String path, @Param("size") long size,
                      @Param("rows") long rows, @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE ReportJob j SET j.status = 'FAILED', j.errorMessage = :msg, j.completedAt = :now "
            + "WHERE j.id = :id AND j.status IN ('QUEUED', 'PROCESSING')")
    int markFailed(@Param("id") UUID id, @Param("msg") String msg, @Param("now") LocalDateTime now);
}
