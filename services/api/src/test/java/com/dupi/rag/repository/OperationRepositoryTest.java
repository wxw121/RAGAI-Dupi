package com.dupi.rag.repository;

import com.dupi.rag.domain.enums.OperationType;
import com.dupi.rag.domain.enums.OperationStatus;
import org.springframework.data.jpa.repository.Query;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OperationRepositoryTest {

    @Test
    void jobRepositoryCanFindAnIdempotentRequest() throws NoSuchMethodException {
        var method = OperationJobRepository.class.getMethod(
                "findByTenantIdAndOperationTypeAndIdempotencyKey",
                String.class,
                OperationType.class,
                String.class
        );

        assertThat(method.getReturnType().getSimpleName()).isEqualTo("Optional");
    }

    @Test
    void stepRepositoryCanFindAJobStepByItsIdempotencyKey() throws NoSuchMethodException {
        var method = OperationStepRepository.class.getMethod(
                "findByJobIdAndStepKey",
                UUID.class,
                String.class
        );

        assertThat(method.getReturnType().getSimpleName()).isEqualTo("Optional");
    }

    @Test
    void jobRepositoryCanLoadDueJobsInCreationOrder() throws NoSuchMethodException {
        var method = OperationJobRepository.class.getMethod(
                "findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc",
                OperationStatus.class,
                Instant.class
        );

        assertThat(method.getReturnType().getSimpleName()).isEqualTo("List");
    }

    @Test
    void jobRepositoryCanCountDueJobs() throws NoSuchMethodException {
        var method = OperationJobRepository.class.getMethod("countDueBefore", Instant.class);

        assertThat(method.getAnnotation(Query.class))
                .isNotNull()
                .extracting(Query::value)
                .asString()
                .contains("job.nextAttemptAt < :time");
    }

    @Test
    void stepRepositoryCanLoadStepsInExecutionOrder() throws NoSuchMethodException {
        var method = OperationStepRepository.class.getMethod("findByJobIdOrderBySequenceNumberAsc", UUID.class);

        assertThat(method.getReturnType().getSimpleName()).isEqualTo("List");
    }
}
