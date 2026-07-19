package com.dupi.rag.repository;

import com.dupi.rag.domain.entity.IngestFailureNotification;
import com.dupi.rag.domain.enums.IngestFailureNotificationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IngestFailureNotificationRepository extends JpaRepository<IngestFailureNotification, UUID> {
    boolean existsByEventKey(String eventKey);
    Optional<IngestFailureNotification> findByEventKey(String eventKey);

    long countByDeliveryStatus(IngestFailureNotificationStatus deliveryStatus);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<IngestFailureNotification> findTop50ByDeliveryStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            List<IngestFailureNotificationStatus> statuses, Instant now);
}
