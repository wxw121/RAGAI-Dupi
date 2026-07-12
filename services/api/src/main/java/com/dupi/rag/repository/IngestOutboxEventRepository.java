package com.dupi.rag.repository;

import com.dupi.rag.domain.entity.IngestOutboxEvent;
import com.dupi.rag.domain.enums.IngestOutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface IngestOutboxEventRepository extends JpaRepository<IngestOutboxEvent, UUID> {
    List<IngestOutboxEvent> findTop50ByStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            List<IngestOutboxStatus> statuses,
            Instant nextAttemptAt
    );
}
