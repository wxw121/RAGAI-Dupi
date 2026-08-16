package com.dupi.rag.repository;

import com.dupi.rag.domain.enums.OperationType;
import com.dupi.rag.domain.enums.OperationStatus;
import org.springframework.data.jpa.repository.Query;
import org.junit.jupiter.api.Test;

import java.lang.reflect.ParameterizedType;
import java.time.Instant;
import java.util.List;
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

        assertThat(method.getGenericReturnType()).isInstanceOf(ParameterizedType.class);
        assertThat(((ParameterizedType) method.getGenericReturnType()).getActualTypeArguments())
                .containsExactly(com.dupi.rag.domain.entity.OperationJob.class);
    }

    @Test
    void stepRepositoryCanFindAJobStepByItsIdempotencyKey() throws NoSuchMethodException {
        var method = OperationStepRepository.class.getMethod(
                "findByJobIdAndStepKey",
                UUID.class,
                String.class
        );

        assertThat(method.getGenericReturnType()).isInstanceOf(ParameterizedType.class);
        assertThat(((ParameterizedType) method.getGenericReturnType()).getActualTypeArguments())
                .containsExactly(com.dupi.rag.domain.entity.OperationStep.class);
    }

    @Test
    void jobRepositoryLoadsOnlyRunnableDueJobsWithStableOrdering() throws NoSuchMethodException {
        var method = OperationJobRepository.class.getMethod(
                "findDueByStatusInOrderByCreatedAtAsc",
                List.class,
                Instant.class
        );

        assertThat(method.getAnnotation(Query.class))
                .isNotNull()
                .extracting(Query::value)
                .asString()
                .contains("job.status in :statuses")
                .contains("job.nextAttemptAt <= :now")
                .contains("order by job.createdAt asc, job.id asc");
        assertThat(OperationJobRepository.RUNNABLE_STATUSES)
                .containsExactly(OperationStatus.PREPARED, OperationStatus.RETRY_WAIT, OperationStatus.COMPENSATING);
    }

    @Test
    void stepRepositoryCanLoadStepsInExecutionOrder() throws NoSuchMethodException {
        var method = OperationStepRepository.class.getMethod("findByJobIdOrderBySequenceNumberAsc", UUID.class);

        assertThat(method.getGenericReturnType()).isInstanceOf(ParameterizedType.class);
        assertThat(((ParameterizedType) method.getGenericReturnType()).getActualTypeArguments())
                .containsExactly(com.dupi.rag.domain.entity.OperationStep.class);
    }

    @Test
    void operationStatusKeepsPreparedWorkDistinctFromPendingSteps() {
        assertThat(OperationStatus.values()).containsExactly(
                OperationStatus.PREPARED,
                OperationStatus.RUNNING,
                OperationStatus.RETRY_WAIT,
                OperationStatus.COMPENSATING,
                OperationStatus.COMPLETED,
                OperationStatus.FAILED
        );
    }
}
