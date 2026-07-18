package com.dupi.rag.repository;

import com.dupi.rag.domain.enums.IngestJobStatus;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IngestJobRepositoryTest {

    @Test
    void findByIdForUpdateUsesExplicitPessimisticQuery() throws NoSuchMethodException {
        var method = IngestJobRepository.class.getMethod("findByIdForUpdate", UUID.class);

        assertThat(method.getAnnotation(Lock.class))
                .isNotNull()
                .extracting(Lock::value)
                .isEqualTo(LockModeType.PESSIMISTIC_WRITE);
        assertThat(method.getAnnotation(Query.class))
                .isNotNull()
                .extracting(Query::value)
                .isEqualTo("select job from IngestJob job where job.id = :id");
    }

    @Test
    void expiredLeaseRecoveryQueryUsesPessimisticWriteLock() throws NoSuchMethodException {
        var method = IngestJobRepository.class.getMethod(
                "findTop20ByStatusAndLeaseExpiresAtBeforeOrderByUpdatedAtAsc",
                IngestJobStatus.class,
                Instant.class
        );

        assertThat(method.getAnnotation(Lock.class))
                .isNotNull()
                .extracting(Lock::value)
                .isEqualTo(LockModeType.PESSIMISTIC_WRITE);
        assertThat(method.getAnnotation(Query.class))
                .isNotNull()
                .extracting(Query::value)
                .asString()
                .contains("job.leaseExpiresAt is null");
    }
}
