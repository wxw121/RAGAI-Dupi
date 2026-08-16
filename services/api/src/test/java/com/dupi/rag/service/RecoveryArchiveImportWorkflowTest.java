package com.dupi.rag.service;

import com.dupi.rag.domain.entity.OperationJob;
import com.dupi.rag.domain.entity.OperationStep;
import com.dupi.rag.domain.enums.OperationPhase;
import com.dupi.rag.domain.enums.OperationStepStatus;
import com.dupi.rag.repository.OperationJobRepository;
import com.dupi.rag.repository.OperationStepRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.Instant;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class RecoveryArchiveImportWorkflowTest {
    @Mock OperationJobRepository jobs; @Mock OperationStepRepository steps; @Mock OperationJobService operations;
    @Mock OperationJobClaimService claims; @Mock RecoveryStorageService storage; @Mock RecoveryManifestService manifests;
    @Mock RecoveryArchiveImportPersistenceService persistence;
    @Test void compensationDeletesStagingAndDeterministicTargetsEvenWhenTheyAreMissing() {
        UUID id = UUID.randomUUID(); OperationExecutionContext context = new OperationExecutionContext(id, UUID.randomUUID(), 2, 1, OperationPhase.COMPENSATION);
        RecoveryArchiveImportPlan plan = new RecoveryArchiveImportPlan(UUID.randomUUID(), "tenant-a", UUID.randomUUID(), Instant.now(), "embedding", 1, Map.of(), "a", "b", "admin", List.of(new RecoveryArchiveImportPlan.Entry("record:chunks", "RECORD", "records/chunks.ndjson", 1, "0".repeat(64))));
        when(jobs.findById(id)).thenReturn(Optional.of(OperationJob.builder().id(id).input(plan.toInput()).build()));
        when(storage.finalKey("tenant-a", id, "records/chunks.ndjson")).thenReturn("archives/tenant-a/" + id + "/records/chunks.ndjson");
        when(storage.finalKey("tenant-a", id, "manifest.json")).thenReturn("archives/tenant-a/" + id + "/manifest.json");
        when(storage.stagingKey(id)).thenReturn("recovery-staging/" + id + ".zip");
        when(steps.findByJobIdOrderBySequenceNumberAsc(id)).thenReturn(List.of(OperationStep.builder().stepKey("stage-zip").status(OperationStepStatus.COMPLETED).build()));
        new RecoveryArchiveImportWorkflow(jobs, steps, operations, claims, storage, manifests, persistence).executeCompensation(context);
        verify(claims, times(2)).renewLease(context);
        verify(storage).deleteIfPresent("archives/tenant-a/" + id + "/records/chunks.ndjson");
        verify(storage).deleteIfPresent("archives/tenant-a/" + id + "/manifest.json");
        verify(storage).deleteIfPresent("recovery-staging/" + id + ".zip");
        verify(operations).compensateStep(context, "stage-zip");
    }

    @Test void retrySkipsCompletedPromotionAndRecoversInterruptedStepWithoutNewKeys() throws Exception {
        UUID id = UUID.randomUUID(); OperationExecutionContext context = new OperationExecutionContext(id, UUID.randomUUID(), 2, 1, OperationPhase.FORWARD);
        byte[] one = "one".getBytes(), two = "two".getBytes();
        RecoveryArchiveImportPlan plan = new RecoveryArchiveImportPlan(UUID.randomUUID(), "tenant-a", UUID.randomUUID(), Instant.now(), "embedding", 1, Map.of(), "a", "b", "admin", List.of(
                new RecoveryArchiveImportPlan.Entry("record:one", "RECORD", "records/one.json", one.length, sha(one)),
                new RecoveryArchiveImportPlan.Entry("record:two", "RECORD", "records/two.json", two.length, sha(two))));
        when(jobs.findById(id)).thenReturn(Optional.of(OperationJob.builder().id(id).input(plan.toInput()).build()));
        when(storage.bucket()).thenReturn("bucket");
        when(storage.finalKey(eq("tenant-a"), eq(id), anyString())).thenAnswer(call -> "archives/tenant-a/" + id + "/" + call.getArgument(2));
        when(storage.stagingKey(id)).thenReturn("recovery-staging/" + id + ".zip");
        when(operations.recordStep(eq(context), anyString(), anyString(), anyString())).thenReturn(
                OperationStep.builder().status(OperationStepStatus.PENDING).build(), OperationStep.builder().status(OperationStepStatus.PENDING).build(),
                OperationStep.builder().status(OperationStepStatus.COMPLETED).build(), OperationStep.builder().status(OperationStepStatus.RUNNING).build());
        when(storage.verify(any())).thenReturn(false, true, false, false);
        when(storage.open(eq("bucket"), contains("recovery-staging"))).thenAnswer(call -> new ByteArrayInputStream(zip(Map.of("records/one.json", one, "records/two.json", two))));
        when(storage.putFinal(eq("tenant-a"), eq(id), eq("records/one.json"), any())).thenReturn(new StoredRecoveryObject("bucket", "archives/tenant-a/" + id + "/records/one.json", one.length, sha(one)));
        when(storage.putFinal(eq("tenant-a"), eq(id), eq("records/two.json"), any())).thenThrow(new IllegalStateException("object store interrupted"));
        RecoveryArchiveImportWorkflow workflow = new RecoveryArchiveImportWorkflow(jobs, steps, operations, claims, storage, new RecoveryManifestService(new com.fasterxml.jackson.databind.ObjectMapper()), persistence);
        assertThatThrownBy(() -> workflow.executeForward(context)).isInstanceOf(CompensateOperationException.class);
        assertThatThrownBy(() -> workflow.executeForward(context)).isInstanceOf(RetryableOperationException.class);
        verify(operations).completeStep(context, "promote-" + sha("records/one.json".getBytes()));
        verify(storage, times(1)).putFinal(eq("tenant-a"), eq(id), eq("records/one.json"), any());
        verify(operations).retryStep(eq(context), eq("promote-" + sha("records/two.json".getBytes())), contains("interrupted"), any());
    }

    private static byte[] zip(Map<String, byte[]> files) throws Exception { ByteArrayOutputStream output = new ByteArrayOutputStream(); try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(output)) { for (var item : files.entrySet()) { zip.putNextEntry(new java.util.zip.ZipEntry(item.getKey())); zip.write(item.getValue()); zip.closeEntry(); } } return output.toByteArray(); }
    private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
}
