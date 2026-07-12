package com.dupi.rag.repository;

import com.dupi.rag.domain.entity.AuditLog;
import com.dupi.rag.domain.enums.AuditLogStatus;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID>, JpaSpecificationExecutor<AuditLog> {
    int deleteByCreatedAtBefore(Instant cutoff);

    long countByStatusAndCreatedAtAfter(AuditLogStatus status, Instant createdAt);
}
