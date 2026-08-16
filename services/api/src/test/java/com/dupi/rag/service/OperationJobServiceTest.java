package com.dupi.rag.service;

import com.dupi.rag.config.SecurityContext;
import com.dupi.rag.config.TenantContext;
import com.dupi.rag.domain.entity.OperationJob;
import com.dupi.rag.domain.entity.OperationStep;
import com.dupi.rag.domain.enums.OperationStatus;
import com.dupi.rag.domain.enums.OperationType;
import com.dupi.rag.exception.ResourceNotFoundException;
import com.dupi.rag.repository.OperationJobRepository;
import com.dupi.rag.repository.OperationStepRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OperationJobServiceTest {

    private final OperationJobRepository jobs = mock(OperationJobRepository.class);
    private final OperationStepRepository steps = mock(OperationStepRepository.class);
    private final OperationJobService service = new OperationJobService(jobs, steps);

    @AfterEach
    void clearContexts() {
        SecurityContext.clear();
        TenantContext.clear();
    }

    @Test
    void getReturnsOrderedStepsForTheCurrentTenantAndAuthorizedKnowledgeBase() {
        UUID jobId = UUID.randomUUID();
        UUID kbId = UUID.randomUUID();
        TenantContext.setTenantId("tenant-a");
        SecurityContext.set("operator", "OPERATOR", List.of("KB_READ"), List.of(kbId.toString()));
        when(jobs.findById(jobId)).thenReturn(Optional.of(job(jobId, kbId)));
        when(steps.findByJobIdOrderBySequenceNumberAsc(jobId)).thenReturn(List.of(
                OperationStep.builder().jobId(jobId).stepKey("prepare").sequenceNumber(1).build()
        ));

        var response = service.get(jobId);

        assertThat(response.getId()).isEqualTo(jobId);
        assertThat(response.getSteps()).extracting(step -> step.getStepKey()).containsExactly("prepare");
    }

    @Test
    void getDoesNotRevealJobsFromAnotherTenant() {
        UUID jobId = UUID.randomUUID();
        TenantContext.setTenantId("tenant-a");
        when(jobs.findById(jobId)).thenReturn(Optional.of(OperationJob.builder()
                .id(jobId).tenantId("tenant-b").aggregateType("OTHER").build()));

        assertThatThrownBy(() -> service.get(jobId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void retryReturnsAFailedJobToPreparedStateForItsAuthorizedOwner() {
        UUID jobId = UUID.randomUUID();
        UUID kbId = UUID.randomUUID();
        TenantContext.setTenantId("tenant-a");
        SecurityContext.set("operator", "OPERATOR", List.of("KB_READ", "MAINTENANCE"), List.of(kbId.toString()));
        OperationJob job = job(jobId, kbId);
        job.setStatus(OperationStatus.FAILED);
        job.setLastError("minio unavailable");
        job.setNextAttemptAt(Instant.now().plusSeconds(600));
        when(jobs.findById(jobId)).thenReturn(Optional.of(job));
        when(steps.findByJobIdOrderBySequenceNumberAsc(jobId)).thenReturn(List.of());

        var response = service.retry(jobId);

        assertThat(response.getStatus()).isEqualTo(OperationStatus.PREPARED);
        assertThat(job.getNextAttemptAt()).isBefore(Instant.now().plusSeconds(1));
        assertThat(job.getLastError()).isNull();
        verify(jobs).save(job);
    }

    @Test
    void createReturnsExistingJobForTheSameTenantTypeAndIdempotencyKey() {
        UUID kbId = UUID.randomUUID();
        TenantContext.setTenantId("tenant-a");
        OperationJob existing = job(UUID.randomUUID(), kbId);
        when(jobs.findByTenantIdAndOperationTypeAndIdempotencyKey(
                "tenant-a", OperationType.RECOVERY_ARCHIVE_IMPORT, "request-1"))
                .thenReturn(Optional.of(existing));
        when(steps.findByJobIdOrderBySequenceNumberAsc(existing.getId())).thenReturn(List.of());

        var response = service.create(OperationType.RECOVERY_ARCHIVE_IMPORT, "KNOWLEDGE_BASE", kbId,
                "request-1", Map.of("source", "upload"), "operator");

        assertThat(response.getId()).isEqualTo(existing.getId());
        verify(jobs, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void recordingAnAlreadyCompletedStepPreservesItsCompletionForWorkflowRetries() {
        UUID jobId = UUID.randomUUID();
        OperationStep completed = OperationStep.builder()
                .id(UUID.randomUUID())
                .jobId(jobId)
                .stepKey("store-archive")
                .stepType("STORE")
                .sequenceNumber(1)
                .status(com.dupi.rag.domain.enums.OperationStepStatus.COMPLETED)
                .completedAt(Instant.now())
                .build();
        when(steps.findByJobIdAndStepKey(jobId, "store-archive")).thenReturn(Optional.of(completed));

        var result = service.recordStep(jobId, "store-archive", "STORE", "archives/job-id.zip");

        assertThat(result.getStatus()).isEqualTo(com.dupi.rag.domain.enums.OperationStepStatus.COMPLETED);
        verify(steps, org.mockito.Mockito.never()).save(any());
    }

    private static OperationJob job(UUID jobId, UUID kbId) {
        return OperationJob.builder()
                .id(jobId)
                .tenantId("tenant-a")
                .operationType(OperationType.RECOVERY_ARCHIVE_IMPORT)
                .aggregateType("KNOWLEDGE_BASE")
                .aggregateId(kbId)
                .status(OperationStatus.PREPARED)
                .idempotencyKey("request-1")
                .input(Map.of())
                .attemptCount(0)
                .nextAttemptAt(Instant.now())
                .createdBy("operator")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }
}
