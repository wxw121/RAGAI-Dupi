package com.dupi.rag.repository;

import com.dupi.rag.domain.entity.UploadQuotaReservation;
import com.dupi.rag.domain.enums.UploadQuotaReservationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;
import java.time.Instant;
import java.util.UUID;

public interface UploadQuotaReservationRepository extends JpaRepository<UploadQuotaReservation, UUID> {

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<UploadQuotaReservation> findById(UUID id);

    Optional<UploadQuotaReservation> findByTenantIdAndUserIdAndKbIdAndIdempotencyKey(
            String tenantId, String userId, UUID kbId, String idempotencyKey);

    @Query(value = "select pg_advisory_xact_lock(hashtext(:tenantId), hashtext(:userId))", nativeQuery = true)
    void lockTenantUserScope(@Param("tenantId") String tenantId, @Param("userId") String userId);

    @Query(value = """
            select * from upload_quota_reservations
            where status = 'PENDING'
              and attempt_id is not null
              and attempt_expires_at is not null
              and attempt_expires_at <= :now
            order by updated_at asc
            limit :limit
            for update skip locked
            """, nativeQuery = true)
    List<UploadQuotaReservation> findStalePendingAttemptsForUpdate(
            @Param("now") Instant now,
            @Param("limit") int limit
    );

    @Query("select coalesce(sum(r.reservedBytes), 0) from UploadQuotaReservation r " +
            "where r.tenantId = :tenantId and r.userId = :userId and r.status = :status")
    long sumBytesByStatus(@Param("tenantId") String tenantId,
                          @Param("userId") String userId,
                          @Param("status") UploadQuotaReservationStatus status);

    @Query("select coalesce(sum(r.reservedBytes), 0) from UploadQuotaReservation r where r.status = :status")
    long sumReservedBytesByStatus(@Param("status") UploadQuotaReservationStatus status);

    @Query("select count(r) from UploadQuotaReservation r " +
            "where r.tenantId = :tenantId and r.userId = :userId and r.status = :status")
    long countByStatus(@Param("tenantId") String tenantId,
                       @Param("userId") String userId,
                       @Param("status") UploadQuotaReservationStatus status);

    long countByStatus(UploadQuotaReservationStatus status);

    @Query("""
            select count(r) from UploadQuotaReservation r
            where r.status = com.dupi.rag.domain.enums.UploadQuotaReservationStatus.PENDING
              and r.attemptId is not null
              and r.attemptExpiresAt is not null
              and r.attemptExpiresAt <= :now
            """)
    long countStalePendingAttempts(@Param("now") Instant now);

    @Query("select coalesce(sum(r.reservedBytes), 0) from UploadQuotaReservation r " +
            "where r.tenantId = :tenantId and r.userId = :userId and r.status in :statuses")
    long sumBytesByStatuses(@Param("tenantId") String tenantId,
                            @Param("userId") String userId,
                            @Param("statuses") List<UploadQuotaReservationStatus> statuses);

    @Query("select count(r) from UploadQuotaReservation r " +
            "where r.tenantId = :tenantId and r.userId = :userId and r.status in :statuses")
    long countByStatuses(@Param("tenantId") String tenantId,
                         @Param("userId") String userId,
                         @Param("statuses") List<UploadQuotaReservationStatus> statuses);

    default long sumCommittedBytes(String tenantId, String userId) {
        return sumBytesByStatus(tenantId, userId, UploadQuotaReservationStatus.COMMITTED);
    }

    default long countCommittedDocuments(String tenantId, String userId) {
        return countByStatus(tenantId, userId, UploadQuotaReservationStatus.COMMITTED);
    }

    default long sumActiveReservedBytes(String tenantId, String userId) {
        return sumBytesByStatuses(tenantId, userId, List.of(
                UploadQuotaReservationStatus.PENDING,
                UploadQuotaReservationStatus.COMMITTED));
    }

    default long countActiveReservedDocuments(String tenantId, String userId) {
        return countByStatuses(tenantId, userId, List.of(
                UploadQuotaReservationStatus.PENDING,
                UploadQuotaReservationStatus.COMMITTED));
    }
}
