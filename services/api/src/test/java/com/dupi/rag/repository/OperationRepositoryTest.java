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
    void governanceDueQueriesMirrorRunnableClaimsIncludingExpiredLeases() throws Exception {
        String countQuery = OperationJobRepository.class.getMethod("countDueBefore", Instant.class)
                .getAnnotation(Query.class).value();
        String oldestQuery = OperationJobRepository.class.getMethod("findOldestDueAt", Instant.class)
                .getAnnotation(Query.class).value();

        assertThat(countQuery)
                .contains("job.runnable = true")
                .contains("job.nextAttemptAt <= :now")
                .contains("OperationStatus.RUNNING")
                .contains("job.leaseExpiresAt <= :now");
        assertThat(oldestQuery)
                .contains("case when job.status =")
                .contains("OperationStatus.RUNNING")
                .contains("then job.leaseExpiresAt")
                .contains("else job.nextAttemptAt")
                .contains("job.leaseExpiresAt <= :now");
    }

    @Test
    void retryAggregationDoesNotCountTheInitialAttemptOfEitherPhase() throws Exception {
        String query = OperationJobRepository.class.getMethod("sumRetryCount")
                .getAnnotation(Query.class).value();

        assertThat(query)
                .contains("job.phase")
                .contains("OperationPhase.COMPENSATION")
                .contains("job.phaseAttemptCount")
                .contains("job.attemptCount - 2");
    }

    @Test
    void retentionScanIsBoundedByPageableAndSelectsOnlyStagedImportIntakes() throws Exception {
        var method = OperationJobRepository.class.getMethod(
                "findExpiredStagingIntakes", Instant.class,
                org.springframework.data.domain.Pageable.class);
        String query = method.getAnnotation(Query.class).value();

        assertThat(query)
                .contains("OperationType.RECOVERY_ARCHIVE_IMPORT")
                .contains("OperationType.MARKDOWN_PACKAGE_IMPORT")
                .contains("OperationStatus.PREPARED")
                .contains("OperationPhase.FORWARD")
                .contains("job.runnable = false")
                .contains("job.createdAt <= :cutoff")
                .contains("order by job.createdAt asc, job.id asc");
        assertThat(method.getParameterTypes()[1])
                .isEqualTo(org.springframework.data.domain.Pageable.class);
    }

    @Test
    void jobRepositoryCanFindAnIdempotentRequest() throws NoSuchMethodException {
        var method = OperationJobRepository.class.getMethod(
                "findByTenantIdAndOperationTypeAndIdempotencyKey",
                String.class,
                OperationType.class,
                String.class
        );

        assertThat(method.getReturnType()).isEqualTo(java.util.Optional.class);
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

        assertThat(method.getReturnType()).isEqualTo(java.util.Optional.class);
        assertThat(method.getGenericReturnType()).isInstanceOf(ParameterizedType.class);
        assertThat(((ParameterizedType) method.getGenericReturnType()).getActualTypeArguments())
                .containsExactly(com.dupi.rag.domain.entity.OperationStep.class);
    }

    @Test
    void jobRepositoryLoadsOnlyRunnableDueJobsWithStableOrdering() throws NoSuchMethodException {
        var method = OperationJobRepository.class.getMethod(
                "findDueByStatusInOrderByCreatedAtAsc",
                Instant.class
        );

        assertThat(method.getReturnType()).isEqualTo(List.class);
        assertThat(method.getGenericReturnType()).isInstanceOf(ParameterizedType.class);
        assertThat(((ParameterizedType) method.getGenericReturnType()).getActualTypeArguments())
                .containsExactly(com.dupi.rag.domain.entity.OperationJob.class);
        assertThat(method.getAnnotation(Query.class))
                .isNotNull()
                .extracting(Query::value)
                .asString()
                .contains("OperationStatus.PREPARED")
                .contains("OperationStatus.RETRY_WAIT")
                .contains("OperationStatus.COMPENSATING")
                .doesNotContain("OperationStatus.RUNNING")
                .doesNotContain("OperationStatus.COMPLETED")
                .doesNotContain("OperationStatus.FAILED")
                .contains("job.nextAttemptAt <= :now")
                .contains("order by job.createdAt asc, job.id asc");
    }

    @Test
    void stepRepositoryCanLoadStepsInExecutionOrder() throws NoSuchMethodException {
        var method = OperationStepRepository.class.getMethod("findByJobIdOrderBySequenceNumberAsc", UUID.class);

        assertThat(method.getReturnType()).isEqualTo(List.class);
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
