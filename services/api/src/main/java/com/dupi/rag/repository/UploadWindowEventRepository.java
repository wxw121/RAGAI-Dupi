package com.dupi.rag.repository;

import com.dupi.rag.domain.entity.UploadWindowEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface UploadWindowEventRepository extends JpaRepository<UploadWindowEvent, UUID> {

    @Query("select coalesce(sum(e.bytes), 0) from UploadWindowEvent e " +
            "where e.tenantId = :tenantId and e.userId = :userId and e.acceptedAt >= :since")
    long sumBytesSince(@Param("tenantId") String tenantId,
                       @Param("userId") String userId,
                       @Param("since") Instant since);
}
