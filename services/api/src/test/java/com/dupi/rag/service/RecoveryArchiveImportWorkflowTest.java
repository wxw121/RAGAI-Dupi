package com.dupi.rag.service;

import com.dupi.rag.domain.entity.OperationJob;
import com.dupi.rag.domain.entity.OperationStep;
import com.dupi.rag.domain.enums.OperationPhase;
import com.dupi.rag.domain.enums.OperationStepStatus;
import com.dupi.rag.domain.enums.OperationStatus;
import com.dupi.rag.domain.enums.OperationType;
import com.dupi.rag.repository.OperationJobRepository;
import com.dupi.rag.repository.OperationStepRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RecoveryArchiveImportWorkflowTest {
    private final OperationJobRepository jobs = mock(OperationJobRepository.class);
    private final OperationStepRepository stepRepository = mock(OperationStepRepository.class);
    private final OperationJobService operations = mock(OperationJobService.class);
    private final OperationJobClaimService claims = mock(OperationJobClaimService.class);
    private final RecoveryStorageService storage = mock(RecoveryStorageService.class);
    private final RecoveryArchiveImportPersistenceService persistence = mock(RecoveryArchiveImportPersistenceService.class);
    private final RecoveryManifestService manifests = new RecoveryManifestService(
            new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
    private final Map<String, OperationStep> stepState = new LinkedHashMap<>();
    private final UUID jobId = UUID.randomUUID();
    private final OperationExecutionContext forward = new OperationExecutionContext(
            jobId, UUID.randomUUID(), 2, 1, OperationPhase.FORWARD);
    private final OperationExecutionContext compensation = new OperationExecutionContext(
            jobId, UUID.randomUUID(), 3, 1, OperationPhase.COMPENSATION);
    private RecoveryArchiveImportPlan plan;
    private RecoveryArchiveImportWorkflow workflow;

    @BeforeEach
    void setUp() throws Exception {
        byte[] bytes = "one".getBytes();
        plan = new RecoveryArchiveImportPlan(UUID.randomUUID(), "tenant-a", UUID.randomUUID(), null,
                "embedding", 3, Map.of(), "a".repeat(64), "b".repeat(64), "admin", List.of(
                new RecoveryArchiveImportPlan.Entry("record:one", "RECORD", "records/one.json",
                        bytes.length, sha(bytes))));
        when(jobs.findById(jobId)).thenReturn(Optional.of(OperationJob.builder().id(jobId).input(plan.toInput()).build()));
        when(claims.renewLease(any())).thenAnswer(call -> call.getArgument(0));
        when(storage.bucket()).thenReturn("bucket");
        when(storage.stagingKey(jobId)).thenReturn("recovery-staging/" + jobId + ".zip");
        when(storage.finalKey(eq("tenant-a"), eq(jobId), anyString()))
                .thenAnswer(call -> "archives/tenant-a/" + jobId + "/" + call.getArgument(2));
        statefulSteps();
        workflow = new RecoveryArchiveImportWorkflow(jobs, stepRepository, operations, claims,
                storage, manifests, persistence);
    }

    @Test
    void temporaryStorageFailureRequestsRetryAndKeepsPromotionReplayable() throws Exception {
        when(storage.inspect(any())).thenReturn(RecoveryStorageOutcome.ABSENT);
        when(storage.open("bucket", "recovery-staging/" + jobId + ".zip"))
                .thenReturn(new ByteArrayInputStream(zip(Map.of("records/one.json", "one".getBytes()))));
        when(storage.putFinal(eq("tenant-a"), eq(jobId), eq("records/one.json"), any()))
                .thenThrow(new RecoveryStorageUnavailableException("timeout", new Exception("down")));

        assertThatThrownBy(() -> workflow.executeForward(forward))
                .isInstanceOf(RetryableOperationException.class).hasMessageContaining("temporarily");
        assertThat(stepState.values()).anyMatch(step -> step.getStatus() == OperationStepStatus.RUNNING);
        verifyNoInteractions(persistence);
    }

    @Test
    void runningPromotionWithAbsentObjectReuploadsSameDeterministicKey() throws Exception {
        String promotion = "promote-" + sha("records/one.json".getBytes());
        stepState.put(promotion, step(promotion, OperationStepStatus.RUNNING));
        when(storage.inspect(any())).thenReturn(RecoveryStorageOutcome.ABSENT, RecoveryStorageOutcome.MATCHING,
                RecoveryStorageOutcome.CONFLICT);
        when(storage.open("bucket", "recovery-staging/" + jobId + ".zip"))
                .thenReturn(new ByteArrayInputStream(zip(Map.of("records/one.json", "one".getBytes()))));
        when(storage.putFinal(eq("tenant-a"), eq(jobId), eq("records/one.json"), any()))
                .thenReturn(new StoredRecoveryObject("bucket",
                        "archives/tenant-a/" + jobId + "/records/one.json", 3, sha("one".getBytes())));

        assertThatThrownBy(() -> workflow.executeForward(forward)).isInstanceOf(CompensateOperationException.class);
        verify(storage).putFinal(eq("tenant-a"), eq(jobId), eq("records/one.json"), any());
        assertThat(stepState.get(promotion).getStatus()).isEqualTo(OperationStepStatus.COMPLETED);
    }

    @Test
    void permanentObjectConflictRequestsCompensationWithoutOverwriting() {
        when(storage.inspect(any())).thenReturn(RecoveryStorageOutcome.CONFLICT);

        assertThatThrownBy(() -> workflow.executeForward(forward)).isInstanceOf(CompensateOperationException.class);
        verify(storage, never()).open(anyString(), anyString());
        verify(storage, never()).putFinal(anyString(), any(), anyString(), any());
    }

    @Test
    void compensationUsesDurableOrderedStepsAndRetriesDeleteOutageWithoutCompleting() {
        String promotion = "promote-" + uncheckedSha("records/one.json".getBytes());
        stepState.put("stage-zip", step("stage-zip", OperationStepStatus.COMPLETED));
        stepState.put(promotion, step(promotion, OperationStepStatus.RUNNING));
        doThrow(new RecoveryStorageUnavailableException("delete timeout", new Exception("down")))
                .when(storage).delete(contains("records/one.json"));

        assertThatThrownBy(() -> workflow.executeCompensation(compensation))
                .isInstanceOf(RetryableOperationException.class);
        assertThat(stepState.get("cleanup-metadata").getStatus()).isEqualTo(OperationStepStatus.COMPLETED);
        assertThat(stepState.values()).anyMatch(step -> step.getStepKey().startsWith("cleanup-object-")
                && step.getStatus() == OperationStepStatus.RETRY_WAIT);
        verify(storage, never()).delete("recovery-staging/" + jobId + ".zip");

        doNothing().when(storage).delete(anyString());
        workflow.executeCompensation(compensation);

        assertThat(stepState.values()).noneMatch(step -> step.getStatus() == OperationStepStatus.RUNNING);
        assertThat(stepState.get(promotion).getStatus()).isEqualTo(OperationStepStatus.COMPENSATED);
        assertThat(stepState.get("stage-zip").getStatus()).isEqualTo(OperationStepStatus.COMPENSATED);
        verify(persistence, times(1)).delete(compensation, plan);
    }

    @Test
    void realRunnerMovesTemporaryStorageFailureToRetryWaitAndConflictToCompensation() throws Exception {
        OperationJob firstJob = claimedJob(OperationPhase.FORWARD);
        when(claims.claimNext()).thenReturn(OperationClaimResult.claimed(firstJob));
        when(storage.inspect(any())).thenReturn(RecoveryStorageOutcome.ABSENT);
        when(storage.open("bucket", "recovery-staging/" + jobId + ".zip"))
                .thenReturn(new ByteArrayInputStream(zip(Map.of("records/one.json", "one".getBytes()))));
        when(storage.putFinal(eq("tenant-a"), eq(jobId), eq("records/one.json"), any()))
                .thenThrow(new RecoveryStorageUnavailableException("timeout", new Exception("down")));
        doAnswer(call -> { firstJob.setStatus(OperationStatus.RETRY_WAIT); return null; })
                .when(claims).scheduleRetry(any(), anyString());

        new OperationJobRunner(claims, List.of(workflow)).runOne();
        assertThat(firstJob.getStatus()).isEqualTo(OperationStatus.RETRY_WAIT);
        assertThat(firstJob.getPhase()).isEqualTo(OperationPhase.FORWARD);
        verify(claims, never()).beginCompensation(any(), anyString());

        reset(claims, storage);
        OperationJob conflictJob = claimedJob(OperationPhase.FORWARD);
        when(claims.claimNext()).thenReturn(OperationClaimResult.claimed(conflictJob));
        when(claims.renewLease(any())).thenAnswer(call -> call.getArgument(0));
        when(storage.bucket()).thenReturn("bucket");
        when(storage.finalKey(eq("tenant-a"), eq(jobId), anyString()))
                .thenAnswer(call -> "archives/tenant-a/" + jobId + "/" + call.getArgument(2));
        when(storage.inspect(any())).thenReturn(RecoveryStorageOutcome.CONFLICT);
        doAnswer(call -> { conflictJob.setPhase(OperationPhase.COMPENSATION); conflictJob.setStatus(OperationStatus.COMPENSATING); return null; })
                .when(claims).beginCompensation(any(), anyString());

        new OperationJobRunner(claims, List.of(workflow)).runOne();
        assertThat(conflictJob.getPhase()).isEqualTo(OperationPhase.COMPENSATION);
        assertThat(conflictJob.getStatus()).isEqualTo(OperationStatus.COMPENSATING);
        verify(claims, never()).complete(any());
    }

    private void statefulSteps() {
        when(operations.recordStep(any(), anyString(), anyString(), anyString())).thenAnswer(call -> {
            String key = call.getArgument(1);
            return stepState.computeIfAbsent(key, value -> step(value, OperationStepStatus.PENDING));
        });
        when(operations.startStep(any(), anyString())).thenAnswer(call -> {
            OperationStep step = stepState.get(call.getArgument(1));
            if (step.getStatus() != OperationStepStatus.COMPLETED) step.setStatus(OperationStepStatus.RUNNING);
            return step;
        });
        doAnswer(call -> { stepState.get(call.getArgument(1)).setStatus(OperationStepStatus.COMPLETED); return null; })
                .when(operations).completeStep(any(), anyString());
        when(operations.retryStep(any(), anyString(), anyString(), any())).thenAnswer(call -> {
            OperationStep step = stepState.get(call.getArgument(1)); step.setStatus(OperationStepStatus.RETRY_WAIT); return step;
        });
        when(operations.compensateStep(any(), anyString())).thenAnswer(call -> {
            OperationStep step = stepState.get(call.getArgument(1)); step.setStatus(OperationStepStatus.COMPENSATED); return step;
        });
        when(stepRepository.findByJobIdOrderBySequenceNumberAsc(jobId))
                .thenAnswer(call -> new ArrayList<>(stepState.values()));
    }

    private OperationStep step(String key, OperationStepStatus status) {
        return OperationStep.builder().jobId(jobId).stepKey(key).stepType("TEST").status(status).build();
    }
    private OperationJob claimedJob(OperationPhase phase) {
        return OperationJob.builder().id(jobId).operationType(OperationType.RECOVERY_ARCHIVE_IMPORT)
                .status(OperationStatus.RUNNING).phase(phase).claimToken(UUID.randomUUID())
                .claimEpoch(3L).retryEpoch(1L).leaseExpiresAt(Instant.now().plusSeconds(30)).build();
    }
    private static byte[] zip(Map<String, byte[]> files) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(output)) {
            for (var item : files.entrySet()) { zip.putNextEntry(new java.util.zip.ZipEntry(item.getKey())); zip.write(item.getValue()); zip.closeEntry(); }
        }
        return output.toByteArray();
    }
    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static String uncheckedSha(byte[] bytes) {
        try { return sha(bytes); } catch (Exception exception) { throw new AssertionError(exception); }
    }
}
