package com.example.ussdGateway.repository;

import com.example.ussdGateway.entity.ApnRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ApnRequestRepository extends JpaRepository<ApnRequest, Long> {

    @Query("SELECT a FROM ApnRequest a WHERE a.createdAt BETWEEN :start AND :end "
            + "AND (:status IS NULL OR a.status = :status) "
            + "AND (:msisdn IS NULL OR a.msisdn LIKE :msisdn) "
            + "AND (:imsi IS NULL OR a.imsi LIKE :imsi) "
            + "ORDER BY a.createdAt DESC")
    Page<ApnRequest> search(@Param("start") LocalDateTime start,
                            @Param("end") LocalDateTime end,
                            @Param("status") String status,
                            @Param("msisdn") String msisdn,
                            @Param("imsi") String imsi,
                            Pageable pageable);

    /** Keyset (cursor-based) search for large exports without the OFFSET penalty. */
    @Query("SELECT a FROM ApnRequest a WHERE a.createdAt BETWEEN :start AND :end "
            + "AND (a.createdAt < :cursorTime OR (a.createdAt = :cursorTime AND a.id < :cursorId)) "
            + "AND (:status IS NULL OR a.status = :status) "
            + "AND (:msisdn IS NULL OR a.msisdn LIKE :msisdn) "
            + "AND (:imsi IS NULL OR a.imsi LIKE :imsi) "
            + "ORDER BY a.createdAt DESC, a.id DESC")
    List<ApnRequest> searchKeyset(@Param("start") LocalDateTime start,
                                  @Param("end") LocalDateTime end,
                                  @Param("status") String status,
                                  @Param("msisdn") String msisdn,
                                  @Param("imsi") String imsi,
                                  @Param("cursorTime") LocalDateTime cursorTime,
                                  @Param("cursorId") Long cursorId,
                                  Pageable pageable);

    @Query("SELECT a FROM ApnRequest a WHERE a.createdAt BETWEEN :start AND :end "
            + "AND (:status IS NULL OR a.status = :status) "
            + "AND (:msisdn IS NULL OR a.msisdn LIKE :msisdn) "
            + "AND (:imsi IS NULL OR a.imsi LIKE :imsi) "
            + "ORDER BY a.createdAt DESC")
    List<ApnRequest> findAllInRange(@Param("start") LocalDateTime start,
                                    @Param("end") LocalDateTime end,
                                    @Param("status") String status,
                                    @Param("msisdn") String msisdn,
                                    @Param("imsi") String imsi);

    @Query("SELECT COUNT(a.id) FROM ApnRequest a WHERE a.createdAt BETWEEN :start AND :end")
    long countInRange(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("SELECT COUNT(a.id) FROM ApnRequest a WHERE a.createdAt BETWEEN :start AND :end AND a.status = :status")
    long countByStatusInRange(@Param("start") LocalDateTime start,
                              @Param("end") LocalDateTime end,
                              @Param("status") String status);

    @Query("SELECT AVG(a.latencyMs) FROM ApnRequest a WHERE a.createdAt BETWEEN :start AND :end AND a.latencyMs IS NOT NULL")
    Double averageLatencyInRange(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query(value = "SELECT cast(created_at as date) as day, count(*) as total, "
            + "count(*) filter (where status = 'SUCCESS') as success, "
            + "count(*) filter (where status = 'THROTTLED') as throttled, "
            + "count(*) filter (where status not in ('SUCCESS', 'THROTTLED')) as failed "
            + "FROM apn_requests WHERE created_at BETWEEN :start AND :end "
            + "GROUP BY cast(created_at as date) ORDER BY day", nativeQuery = true)
    List<DailyApnStatsProjection> countByDayInRange(@Param("start") LocalDateTime start,
                                                    @Param("end") LocalDateTime end);

    @Query(value = "SELECT status, count(*) as total FROM apn_requests "
            + "WHERE created_at BETWEEN :start AND :end GROUP BY status ORDER BY total DESC", nativeQuery = true)
    List<StatusCountProjection> countByStatusGroup(@Param("start") LocalDateTime start,
                                                  @Param("end") LocalDateTime end);

    interface DailyApnStatsProjection {
        java.sql.Date getDay();
        Long getTotal();
        Long getSuccess();
        Long getThrottled();
        Long getFailed();
    }

    interface StatusCountProjection {
        String getStatus();
        Long getTotal();
    }
}
