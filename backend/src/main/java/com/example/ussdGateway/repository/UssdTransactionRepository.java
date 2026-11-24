package com.example.ussdGateway.repository;

import com.example.ussdGateway.entity.UssdTransaction;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface UssdTransactionRepository extends JpaRepository<UssdTransaction, Long> {

    Optional<UssdTransaction> findByCallId(String callId);

    /** Batch lookup used during ingestion to find already-known callIds in one query. */
    List<UssdTransaction> findByCallIdIn(Collection<String> callIds);

    /** High-speed batch deletion for retention policy to prevent table and index bloat. */
    @Modifying
    @Query(value = "DELETE FROM ussd_transactions WHERE id IN "
            + "(SELECT id FROM ussd_transactions WHERE triggered_at < :cutoff ORDER BY triggered_at ASC, id ASC LIMIT :batchSize)",
            nativeQuery = true)
    int deleteBatchByTriggeredAtBefore(@Param("cutoff") LocalDateTime cutoff, @Param("batchSize") int batchSize);

    @Query(value = "SELECT t FROM UssdTransaction t WHERE t.triggeredAt BETWEEN :start AND :end "
            + "AND (:nodeIp IS NULL OR t.nodeIp = :nodeIp) "
            + "AND (:status IS NULL OR t.status = :status) "
            + "AND (:shortCode IS NULL OR LOWER(t.shortCode) LIKE :shortCode) "
            + "AND (:msisdn IS NULL OR t.msisdn LIKE :msisdn) "
            + "AND (:errorCode IS NULL OR LOWER(t.errorCode) LIKE :errorCode) "
            + "AND (:productName IS NULL OR LOWER(t.productName) LIKE :productName) "
            + "AND (:message IS NULL OR LOWER(t.errorMessage) LIKE :message OR LOWER(t.errorCode) LIKE :message "
            + "     OR LOWER(t.tibcoResponse) LIKE :message OR LOWER(t.errorReason) LIKE :message OR LOWER(t.subscriberReply) LIKE :message) "
            + "ORDER BY t.triggeredAt DESC, t.id DESC",
            // Explicit countQuery prevents Spring Data from deriving a COUNT(*) by wrapping the
            // full filtered SELECT — which would fire an extra full-scan on every page request.
            countQuery = "SELECT COUNT(t) FROM UssdTransaction t WHERE t.triggeredAt BETWEEN :start AND :end "
            + "AND (:nodeIp IS NULL OR t.nodeIp = :nodeIp) "
            + "AND (:status IS NULL OR t.status = :status) "
            + "AND (:shortCode IS NULL OR LOWER(t.shortCode) LIKE :shortCode) "
            + "AND (:msisdn IS NULL OR t.msisdn LIKE :msisdn) "
            + "AND (:errorCode IS NULL OR LOWER(t.errorCode) LIKE :errorCode) "
            + "AND (:productName IS NULL OR LOWER(t.productName) LIKE :productName) "
            + "AND (:message IS NULL OR LOWER(t.errorMessage) LIKE :message OR LOWER(t.errorCode) LIKE :message "
            + "     OR LOWER(t.tibcoResponse) LIKE :message OR LOWER(t.errorReason) LIKE :message OR LOWER(t.subscriberReply) LIKE :message)")
    Page<UssdTransaction> search(@Param("start") LocalDateTime start,
                                 @Param("end") LocalDateTime end,
                                 @Param("nodeIp") String nodeIp,
                                 @Param("status") String status,
                                 @Param("shortCode") String shortCode,
                                 @Param("msisdn") String msisdn,
                                 @Param("errorCode") String errorCode,
                                 @Param("productName") String productName,
                                 @Param("message") String message,
                                 Pageable pageable);

    /** Keyset (cursor-based) search for high-throughput export without OFFSET penalty. */
    @Query("SELECT t FROM UssdTransaction t WHERE t.triggeredAt BETWEEN :start AND :end "
            + "AND (t.triggeredAt < :cursorTime OR (t.triggeredAt = :cursorTime AND t.id < :cursorId)) "
            + "AND (:nodeIp IS NULL OR t.nodeIp = :nodeIp) "
            + "AND (:status IS NULL OR t.status = :status) "
            + "AND (:shortCode IS NULL OR LOWER(t.shortCode) LIKE :shortCode) "
            + "AND (:msisdn IS NULL OR t.msisdn LIKE :msisdn) "
            + "AND (:errorCode IS NULL OR LOWER(t.errorCode) LIKE :errorCode) "
            + "AND (:productName IS NULL OR LOWER(t.productName) LIKE :productName) "
            + "AND (:message IS NULL OR LOWER(t.errorMessage) LIKE :message OR LOWER(t.errorCode) LIKE :message "
            + "     OR LOWER(t.tibcoResponse) LIKE :message OR LOWER(t.errorReason) LIKE :message OR LOWER(t.subscriberReply) LIKE :message) "
            + "ORDER BY t.triggeredAt DESC, t.id DESC")
    List<UssdTransaction> searchKeyset(@Param("start") LocalDateTime start,
                                       @Param("end") LocalDateTime end,
                                       @Param("nodeIp") String nodeIp,
                                       @Param("status") String status,
                                       @Param("shortCode") String shortCode,
                                       @Param("msisdn") String msisdn,
                                       @Param("errorCode") String errorCode,
                                       @Param("productName") String productName,
                                       @Param("message") String message,
                                       @Param("cursorTime") LocalDateTime cursorTime,
                                       @Param("cursorId") Long cursorId,
                                       Pageable pageable);

    /** Consolidated single-pass scalar metrics (total, success count, average latency). */
    @Query("SELECT COUNT(t) AS total, "
            + "SUM(CASE WHEN t.status = 'SUCCESS' THEN 1L ELSE 0L END) AS successCount, "
            + "AVG(t.latencyMs) AS avgLatency "
            + "FROM UssdTransaction t WHERE t.triggeredAt BETWEEN :start AND :end "
            + "AND (:nodeIp IS NULL OR t.nodeIp = :nodeIp) "
            + "AND (:status IS NULL OR t.status = :status) "
            + "AND (:shortCode IS NULL OR LOWER(t.shortCode) LIKE :shortCode) "
            + "AND (:msisdn IS NULL OR t.msisdn LIKE :msisdn) "
            + "AND (:errorCode IS NULL OR LOWER(t.errorCode) LIKE :errorCode) "
            + "AND (:productName IS NULL OR LOWER(t.productName) LIKE :productName) "
            + "AND (:message IS NULL OR LOWER(t.errorMessage) LIKE :message OR LOWER(t.errorCode) LIKE :message "
            + "     OR LOWER(t.tibcoResponse) LIKE :message OR LOWER(t.errorReason) LIKE :message OR LOWER(t.subscriberReply) LIKE :message)")
    SummaryStats consolidatedSummaryStats(@Param("start") LocalDateTime start,
                                          @Param("end") LocalDateTime end,
                                          @Param("nodeIp") String nodeIp,
                                          @Param("status") String status,
                                          @Param("shortCode") String shortCode,
                                          @Param("msisdn") String msisdn,
                                          @Param("errorCode") String errorCode,
                                          @Param("productName") String productName,
                                          @Param("message") String message);

    /** Same filters as {@link #search}, unpaged — used to build report summaries/exports. */
    @Query("SELECT t FROM UssdTransaction t WHERE t.triggeredAt BETWEEN :start AND :end "
            + "AND (:nodeIp IS NULL OR t.nodeIp = :nodeIp) "
            + "AND (:status IS NULL OR t.status = :status) "
            + "AND (:shortCode IS NULL OR LOWER(t.shortCode) LIKE :shortCode) "
            + "AND (:msisdn IS NULL OR t.msisdn LIKE :msisdn) "
            + "AND (:errorCode IS NULL OR LOWER(t.errorCode) LIKE :errorCode) "
            + "AND (:productName IS NULL OR LOWER(t.productName) LIKE :productName) "
            + "AND (:message IS NULL OR LOWER(t.errorMessage) LIKE :message OR LOWER(t.errorCode) LIKE :message "
            + "     OR LOWER(t.tibcoResponse) LIKE :message OR LOWER(t.errorReason) LIKE :message OR LOWER(t.subscriberReply) LIKE :message) "
            + "ORDER BY t.triggeredAt DESC")
    List<UssdTransaction> findAllInRange(@Param("start") LocalDateTime start,
                                         @Param("end") LocalDateTime end,
                                         @Param("nodeIp") String nodeIp,
                                         @Param("status") String status,
                                         @Param("shortCode") String shortCode,
                                         @Param("msisdn") String msisdn,
                                         @Param("errorCode") String errorCode,
                                         @Param("productName") String productName,
                                         @Param("message") String message);

    @Query("SELECT COUNT(t) FROM UssdTransaction t WHERE t.triggeredAt BETWEEN :start AND :end "
            + "AND (:nodeIp IS NULL OR t.nodeIp = :nodeIp) "
            + "AND (:status IS NULL OR t.status = :status) "
            + "AND (:shortCode IS NULL OR LOWER(t.shortCode) LIKE :shortCode) "
            + "AND (:msisdn IS NULL OR t.msisdn LIKE :msisdn) "
            + "AND (:errorCode IS NULL OR LOWER(t.errorCode) LIKE :errorCode) "
            + "AND (:productName IS NULL OR LOWER(t.productName) LIKE :productName) "
            + "AND (:message IS NULL OR LOWER(t.errorMessage) LIKE :message OR LOWER(t.errorCode) LIKE :message "
            + "     OR LOWER(t.tibcoResponse) LIKE :message OR LOWER(t.errorReason) LIKE :message OR LOWER(t.subscriberReply) LIKE :message)")
    long countInRangeWithFilters(@Param("start") LocalDateTime start,
                                 @Param("end") LocalDateTime end,
                                 @Param("nodeIp") String nodeIp,
                                 @Param("status") String status,
                                 @Param("shortCode") String shortCode,
                                 @Param("msisdn") String msisdn,
                                 @Param("errorCode") String errorCode,
                                 @Param("productName") String productName,
                                 @Param("message") String message);

    @Query("SELECT COUNT(t) FROM UssdTransaction t WHERE t.triggeredAt BETWEEN :start AND :end "
            + "AND (:nodeIp IS NULL OR t.nodeIp = :nodeIp) "
            + "AND t.status = 'SUCCESS' "
            + "AND (:shortCode IS NULL OR LOWER(t.shortCode) LIKE :shortCode) "
            + "AND (:msisdn IS NULL OR t.msisdn LIKE :msisdn) "
            + "AND (:errorCode IS NULL OR LOWER(t.errorCode) LIKE :errorCode) "
            + "AND (:productName IS NULL OR LOWER(t.productName) LIKE :productName) "
            + "AND (:message IS NULL OR LOWER(t.errorMessage) LIKE :message OR LOWER(t.errorCode) LIKE :message "
            + "     OR LOWER(t.tibcoResponse) LIKE :message OR LOWER(t.errorReason) LIKE :message OR LOWER(t.subscriberReply) LIKE :message)")
    long countSuccessWithFilters(@Param("start") LocalDateTime start,
                                 @Param("end") LocalDateTime end,
                                 @Param("nodeIp") String nodeIp,
                                 @Param("shortCode") String shortCode,
                                 @Param("msisdn") String msisdn,
                                 @Param("errorCode") String errorCode,
                                 @Param("productName") String productName,
                                 @Param("message") String message);

    @Query("SELECT AVG(t.latencyMs) FROM UssdTransaction t WHERE t.triggeredAt BETWEEN :start AND :end "
            + "AND (:nodeIp IS NULL OR t.nodeIp = :nodeIp) "
            + "AND (:status IS NULL OR t.status = :status) "
            + "AND (:shortCode IS NULL OR LOWER(t.shortCode) LIKE :shortCode) "
            + "AND (:msisdn IS NULL OR t.msisdn LIKE :msisdn) "
            + "AND (:errorCode IS NULL OR LOWER(t.errorCode) LIKE :errorCode) "
            + "AND (:productName IS NULL OR LOWER(t.productName) LIKE :productName) "
            + "AND (:message IS NULL OR LOWER(t.errorMessage) LIKE :message OR LOWER(t.errorCode) LIKE :message "
            + "     OR LOWER(t.tibcoResponse) LIKE :message OR LOWER(t.errorReason) LIKE :message OR LOWER(t.subscriberReply) LIKE :message) "
            + "AND t.latencyMs IS NOT NULL")
    Double averageLatencyWithFilters(@Param("start") LocalDateTime start,
                                     @Param("end") LocalDateTime end,
                                     @Param("nodeIp") String nodeIp,
                                     @Param("status") String status,
                                     @Param("shortCode") String shortCode,
                                     @Param("msisdn") String msisdn,
                                     @Param("errorCode") String errorCode,
                                     @Param("productName") String productName,
                                     @Param("message") String message);

    @Query("SELECT t.status AS status, COUNT(t) AS total FROM UssdTransaction t "
            + "WHERE t.triggeredAt BETWEEN :start AND :end AND (:nodeIp IS NULL OR t.nodeIp = :nodeIp) "
            + "GROUP BY t.status")
    List<StatusCount> countByStatusInRange(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end, @Param("nodeIp") String nodeIp);

    @Query("SELECT CAST(t.triggeredAt AS LocalDate) AS day, COUNT(t) AS total, "
            + "SUM(CASE WHEN t.status = 'SUCCESS' THEN 1 ELSE 0 END) AS success FROM UssdTransaction t "
            + "WHERE t.triggeredAt BETWEEN :start AND :end "
            + "AND (:nodeIp IS NULL OR t.nodeIp = :nodeIp) "
            + "AND (:status IS NULL OR t.status = :status) "
            + "AND (:shortCode IS NULL OR LOWER(t.shortCode) LIKE :shortCode) "
            + "AND (:msisdn IS NULL OR t.msisdn LIKE :msisdn) "
            + "AND (:errorCode IS NULL OR LOWER(t.errorCode) LIKE :errorCode) "
            + "AND (:productName IS NULL OR LOWER(t.productName) LIKE :productName) "
            + "AND (:message IS NULL OR LOWER(t.errorMessage) LIKE :message OR LOWER(t.errorCode) LIKE :message "
            + "     OR LOWER(t.tibcoResponse) LIKE :message OR LOWER(t.errorReason) LIKE :message OR LOWER(t.subscriberReply) LIKE :message) "
            + "GROUP BY CAST(t.triggeredAt AS LocalDate) ORDER BY CAST(t.triggeredAt AS LocalDate)")
    List<DailyCount> countByDayWithFilters(@Param("start") LocalDateTime start,
                                           @Param("end") LocalDateTime end,
                                           @Param("nodeIp") String nodeIp,
                                           @Param("status") String status,
                                           @Param("shortCode") String shortCode,
                                           @Param("msisdn") String msisdn,
                                           @Param("errorCode") String errorCode,
                                           @Param("productName") String productName,
                                           @Param("message") String message);

    @Query("SELECT t.errorCode AS errorCode, COUNT(t) AS total FROM UssdTransaction t "
            + "WHERE t.triggeredAt BETWEEN :start AND :end "
            + "AND (:nodeIp IS NULL OR t.nodeIp = :nodeIp) "
            + "AND (:status IS NULL OR t.status = :status) "
            + "AND (:shortCode IS NULL OR LOWER(t.shortCode) LIKE :shortCode) "
            + "AND (:msisdn IS NULL OR t.msisdn LIKE :msisdn) "
            + "AND (:errorCode IS NULL OR LOWER(t.errorCode) LIKE :errorCode) "
            + "AND (:productName IS NULL OR LOWER(t.productName) LIKE :productName) "
            + "AND (:message IS NULL OR LOWER(t.errorMessage) LIKE :message OR LOWER(t.errorCode) LIKE :message "
            + "     OR LOWER(t.tibcoResponse) LIKE :message OR LOWER(t.errorReason) LIKE :message OR LOWER(t.subscriberReply) LIKE :message) "
            + "AND t.errorCode IS NOT NULL GROUP BY t.errorCode ORDER BY COUNT(t) DESC")
    List<ErrorCodeCount> topErrorCodesWithFilters(@Param("start") LocalDateTime start,
                                                  @Param("end") LocalDateTime end,
                                                  @Param("nodeIp") String nodeIp,
                                                  @Param("status") String status,
                                                  @Param("shortCode") String shortCode,
                                                  @Param("msisdn") String msisdn,
                                                  @Param("errorCode") String errorCode,
                                                  @Param("productName") String productName,
                                                  @Param("message") String message,
                                                  Pageable pageable);

    @Query(value = "SELECT COALESCE(NULLIF(error_message, ''), NULLIF(tibco_response, ''), NULLIF(error_reason, ''), error_code) "
            + "FROM ussd_transactions "
            + "WHERE triggered_at BETWEEN :start AND :end AND error_code = :errorCode "
            + "AND ((error_message IS NOT NULL AND error_message <> '') OR (tibco_response IS NOT NULL AND tibco_response <> '')) "
            + "ORDER BY triggered_at DESC, id DESC LIMIT 1", nativeQuery = true)
    String findSampleErrorMessage(@Param("start") LocalDateTime start,
                                  @Param("end") LocalDateTime end,
                                  @Param("errorCode") String errorCode);

    @Query("SELECT t.shortCode AS shortCode, MAX(t.productName) AS productName, COUNT(t) AS total, "
            + "SUM(CASE WHEN t.status = 'SUCCESS' THEN 1 ELSE 0 END) AS success FROM UssdTransaction t "
            + "WHERE t.triggeredAt BETWEEN :start AND :end "
            + "AND (:nodeIp IS NULL OR t.nodeIp = :nodeIp) "
            + "AND (:status IS NULL OR t.status = :status) "
            + "AND (:shortCode IS NULL OR LOWER(t.shortCode) LIKE :shortCode) "
            + "AND (:msisdn IS NULL OR t.msisdn LIKE :msisdn) "
            + "AND (:errorCode IS NULL OR LOWER(t.errorCode) LIKE :errorCode) "
            + "AND (:productName IS NULL OR LOWER(t.productName) LIKE :productName) "
            + "AND (:message IS NULL OR LOWER(t.errorMessage) LIKE :message OR LOWER(t.errorCode) LIKE :message) "
            + "AND t.shortCode IS NOT NULL GROUP BY t.shortCode ORDER BY COUNT(t) DESC")
    List<ShortCodeCount> topShortCodesWithFilters(@Param("start") LocalDateTime start,
                                                  @Param("end") LocalDateTime end,
                                                  @Param("nodeIp") String nodeIp,
                                                  @Param("status") String status,
                                                  @Param("shortCode") String shortCode,
                                                  @Param("msisdn") String msisdn,
                                                  @Param("errorCode") String errorCode,
                                                  @Param("productName") String productName,
                                                  @Param("message") String message,
                                                  Pageable pageable);

    @Query("SELECT t.productName AS productName, MAX(t.shortCode) AS shortCode, COUNT(t) AS total, "
            + "SUM(CASE WHEN t.status = 'SUCCESS' THEN 1 ELSE 0 END) AS success FROM UssdTransaction t "
            + "WHERE t.triggeredAt BETWEEN :start AND :end "
            + "AND (:nodeIp IS NULL OR t.nodeIp = :nodeIp) "
            + "AND (:status IS NULL OR t.status = :status) "
            + "AND (:shortCode IS NULL OR LOWER(t.shortCode) LIKE :shortCode) "
            + "AND (:msisdn IS NULL OR t.msisdn LIKE :msisdn) "
            + "AND (:errorCode IS NULL OR LOWER(t.errorCode) LIKE :errorCode) "
            + "AND (:productName IS NULL OR LOWER(t.productName) LIKE :productName) "
            + "AND (:message IS NULL OR LOWER(t.errorMessage) LIKE :message OR LOWER(t.errorCode) LIKE :message) "
            + "AND t.productName IS NOT NULL AND t.productName <> '' GROUP BY t.productName ORDER BY COUNT(t) DESC")
    List<PackageCount> topPackagesWithFilters(@Param("start") LocalDateTime start,
                                              @Param("end") LocalDateTime end,
                                              @Param("nodeIp") String nodeIp,
                                              @Param("status") String status,
                                              @Param("shortCode") String shortCode,
                                              @Param("msisdn") String msisdn,
                                              @Param("errorCode") String errorCode,
                                              @Param("productName") String productName,
                                              @Param("message") String message,
                                              Pageable pageable);

    @Query("SELECT t.nodeIp AS nodeIp, COUNT(t) AS total FROM UssdTransaction t "
            + "WHERE t.triggeredAt BETWEEN :start AND :end "
            + "AND (:nodeIp IS NULL OR t.nodeIp = :nodeIp) "
            + "AND (:status IS NULL OR t.status = :status) "
            + "AND (:shortCode IS NULL OR LOWER(t.shortCode) LIKE :shortCode) "
            + "AND (:msisdn IS NULL OR t.msisdn LIKE :msisdn) "
            + "AND (:errorCode IS NULL OR LOWER(t.errorCode) LIKE :errorCode) "
            + "AND (:productName IS NULL OR LOWER(t.productName) LIKE :productName) "
            + "AND (:message IS NULL OR LOWER(t.errorMessage) LIKE :message OR LOWER(t.errorCode) LIKE :message) "
            + "GROUP BY t.nodeIp")
    List<NodeCount> countByNodeWithFilters(@Param("start") LocalDateTime start,
                                           @Param("end") LocalDateTime end,
                                           @Param("nodeIp") String nodeIp,
                                           @Param("status") String status,
                                           @Param("shortCode") String shortCode,
                                           @Param("msisdn") String msisdn,
                                           @Param("errorCode") String errorCode,
                                           @Param("productName") String productName,
                                           @Param("message") String message);

    /**
     * Returns distinct short codes observed in the last 90 days.
     * Date-bounded to avoid a full-table scan growing forever as the table grows.
     */
    @Query(value = "SELECT DISTINCT short_code FROM ussd_transactions "
            + "WHERE short_code IS NOT NULL AND short_code <> '' "
            + "AND triggered_at >= NOW() - INTERVAL '90 days' "
            + "ORDER BY short_code LIMIT 500", nativeQuery = true)
    List<String> findDistinctShortCodes();

    /**
     * Returns distinct product/package names observed in the last 90 days.
     * Date-bounded to avoid a full-table scan growing forever as the table grows.
     */
    @Query(value = "SELECT DISTINCT product_name FROM ussd_transactions "
            + "WHERE product_name IS NOT NULL AND product_name <> '' "
            + "AND triggered_at >= NOW() - INTERVAL '90 days' "
            + "ORDER BY product_name LIMIT 500", nativeQuery = true)
    List<String> findDistinctProductNames();

    /**
     * Returns distinct error codes observed in the last 90 days.
     * Date-bounded to avoid a full-table scan growing forever as the table grows.
     */
    @Query(value = "SELECT DISTINCT error_code FROM ussd_transactions "
            + "WHERE error_code IS NOT NULL AND error_code <> '' "
            + "AND triggered_at >= NOW() - INTERVAL '90 days' "
            + "ORDER BY error_code LIMIT 500", nativeQuery = true)
    List<String> findDistinctErrorCodes();

    @Query(value = "SELECT CAST(EXTRACT(HOUR FROM triggered_at) AS int) AS hour, "
            + "COUNT(*) AS total, "
            + "COUNT(*) FILTER (WHERE status = 'SUCCESS') AS success, "
            + "COUNT(*) FILTER (WHERE status = 'FAILED') AS failed "
            + "FROM ussd_transactions "
            + "WHERE triggered_at BETWEEN :start AND :end "
            + "GROUP BY EXTRACT(HOUR FROM triggered_at) "
            + "ORDER BY hour", nativeQuery = true)
    List<HourlyStatsProjection> countHourlyStats(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query(value = "SELECT COALESCE(NULLIF(tibco_response, ''), NULLIF(error_message, ''), error_code, 'Unknown Error') AS description, "
            + "COUNT(*) AS total, "
            + "MIN(triggered_at) AS \"firstSeen\", "
            + "MIN(triggered_at) AS first_seen, "
            + "MAX(triggered_at) AS \"lastSeen\", "
            + "MAX(triggered_at) AS last_seen "
            + "FROM ussd_transactions "
            + "WHERE triggered_at BETWEEN :start AND :end AND status = 'FAILED' "
            + "GROUP BY COALESCE(NULLIF(tibco_response, ''), NULLIF(error_message, ''), error_code, 'Unknown Error') "
            + "ORDER BY total DESC", nativeQuery = true)
    List<ErrorDescriptionProjection> countErrorsByDescription(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query(value = "SELECT COALESCE(NULLIF(short_code, ''), NULLIF(product_name, ''), 'Unknown') AS \"shortCode\", "
            + "COALESCE(NULLIF(short_code, ''), NULLIF(product_name, ''), 'Unknown') AS short_code, "
            + "COUNT(*) AS total "
            + "FROM ussd_transactions "
            + "WHERE triggered_at BETWEEN :start AND :end AND status = 'FAILED' "
            + "GROUP BY COALESCE(NULLIF(short_code, ''), NULLIF(product_name, ''), 'Unknown') "
            + "ORDER BY total DESC", nativeQuery = true)
    List<ShortCodeErrorProjection> countErrorsByShortCode(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query(value = "SELECT "
            + "COUNT(*) FILTER (WHERE tibco_response ILIKE '%Read timed out%' OR error_message ILIKE '%Read timed out%' OR error_code = 'READ_TIMEOUT') AS readTimedOut, "
            + "COUNT(*) FILTER (WHERE tibco_response ILIKE '%504%' OR tibco_response ILIKE '%Time-out%' OR error_message ILIKE '%504%' OR error_message ILIKE '%Time-out%' OR error_code = 'HTTP_504') AS error504 "
            + "FROM ussd_transactions "
            + "WHERE triggered_at BETWEEN :start AND :end AND status = 'FAILED'", nativeQuery = true)
    SpecificErrorCounts countSpecificErrors(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query(value = "SELECT COUNT(*) FROM ussd_transactions "
            + "WHERE triggered_at BETWEEN :start AND :end AND status = 'FAILED' "
            + "AND (tibco_response ILIKE '%Read timed out%' OR error_message ILIKE '%Read timed out%' OR error_code = 'READ_TIMEOUT')", nativeQuery = true)
    long countReadTimedOutErrors(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query(value = "SELECT COUNT(*) FROM ussd_transactions "
            + "WHERE triggered_at BETWEEN :start AND :end AND status = 'FAILED' "
            + "AND (tibco_response ILIKE '%504%' OR tibco_response ILIKE '%Time-out%' OR error_message ILIKE '%504%' OR error_message ILIKE '%Time-out%' OR error_code = 'HTTP_504')", nativeQuery = true)
    long count504Errors(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    interface SpecificErrorCounts {
        Long getReadTimedOut();
        Long getError504();
    }

    interface SummaryStats {
        Long getTotal();
        Long getSuccessCount();
        Double getAvgLatency();
    }

    interface StatusCount {
        String getStatus();
        long getTotal();
    }

    interface DailyCount {
        Object getDay();
        long getTotal();
        Long getSuccess();
    }

    interface ErrorCodeCount {
        String getErrorCode();
        long getTotal();
        String getSampleMessage();
    }

    interface ShortCodeCount {
        String getShortCode();
        String getProductName();
        long getTotal();
        Long getSuccess();
    }

    interface PackageCount {
        String getProductName();
        String getShortCode();
        long getTotal();
        Long getSuccess();
    }

    interface NodeCount {
        String getNodeIp();
        long getTotal();
    }

    interface HourlyStatsProjection {
        Integer getHour();
        Long getTotal();
        Long getSuccess();
        Long getFailed();
    }

    interface ErrorDescriptionProjection {
        @Value("#{target.description}")
        String getDescription();

        @Value("#{target.total}")
        Long getTotal();

        @Value("#{target.firstSeen != null ? target.firstSeen : (target.first_seen != null ? target.first_seen : target.firstseen)}")
        Object getFirstSeen();

        @Value("#{target.lastSeen != null ? target.lastSeen : (target.last_seen != null ? target.last_seen : target.lastseen)}")
        Object getLastSeen();
    }

    interface ShortCodeErrorProjection {
        @Value("#{target.shortCode != null ? target.shortCode : (target.short_code != null ? target.short_code : target.shortcode)}")
        String getShortCode();

        @Value("#{target.total}")
        Long getTotal();
    }
}
