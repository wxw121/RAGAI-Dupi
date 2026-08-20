package com.dupi.rag.service;

import com.dupi.rag.domain.entity.OperationJob;
import com.dupi.rag.domain.entity.OperationStep;
import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.IngestJob;
import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.domain.entity.UploadQuotaReservation;
import com.dupi.rag.domain.enums.DocumentStatus;
import com.dupi.rag.domain.enums.OperationPhase;
import com.dupi.rag.domain.enums.OperationStatus;
import com.dupi.rag.domain.enums.OperationStepStatus;
import com.dupi.rag.repository.DocumentAssetRepository;
import com.dupi.rag.repository.DocumentRepository;
import com.dupi.rag.repository.IngestJobRepository;
import com.dupi.rag.repository.IngestOutboxEventRepository;
import com.dupi.rag.repository.KnowledgeBaseRepository;
import com.dupi.rag.repository.OperationJobRepository;
import com.dupi.rag.repository.OperationStepRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class MarkdownPackageImportWorkflowTest {

    @Test
    void preparesStoresAndAtomicallyPublishesAValidatedPlan() throws Exception {
        Fixture fixture = fixture(plan("guide.md", "hello"));
        when(fixture.storage.inspect(anyString(), anyLong(), anyString()))
                .thenReturn(absent(), matching());
        when(fixture.storage.download(anyString())).thenReturn(new ByteArrayInputStream("hello".getBytes()));

        fixture.workflow.executeForward(fixture.context);

        verify(fixture.persistence).prepare(eq(fixture.context),
                argThat(actual -> actual.toInput().equals(fixture.plan.toInput())), eq("prepare-metadata"));
        verify(fixture.storage).uploadIfAbsent(eq(fixture.plan.documents().get(0).objectKey()), any(), eq(5L), eq("text/markdown"));
        verify(fixture.persistence).publish(eq(fixture.context),
                argThat(actual -> actual.toInput().equals(fixture.plan.toInput())), eq("publish"));
        assertThat(fixture.workflow.completionMode(fixture.context)).isEqualTo(OperationCompletionMode.DOMAIN_TRANSACTION);
    }

    @Test
    void temporaryMiddleStoreFailureIsRetryableAndImportRemainsInvisible() throws Exception {
        MarkdownImportPlan plan = plan("a.md", "a", "b.md", "b");
        Fixture fixture = fixture(plan);
        when(fixture.storage.inspect(anyString(), anyLong(), anyString()))
                .thenReturn(absent(), matching(), absent());
        when(fixture.storage.download(anyString())).thenReturn(new ByteArrayInputStream("x".getBytes()));
        when(fixture.storage.uploadIfAbsent(anyString(), any(), anyLong(), anyString()))
                .thenReturn(MinioStorageService.ObjectWriteResult.CREATED)
                .thenThrow(new IllegalStateException("minio down"));

        assertThatThrownBy(() -> fixture.workflow.executeForward(fixture.context))
                .isInstanceOf(RetryableOperationException.class)
                .hasMessageContaining("temporarily unavailable");
        verify(fixture.persistence, never()).publish(any(), any(), anyString());
    }

    @Test
    void conflictingDeterministicObjectRequestsCompensationWithoutOverwriting() throws Exception {
        Fixture fixture = fixture(plan("guide.md", "hello"));
        when(fixture.storage.inspect(anyString(), anyLong(), anyString())).thenReturn(conflict());

        assertThatThrownBy(() -> fixture.workflow.executeForward(fixture.context))
                .isInstanceOf(CompensateOperationException.class)
                .hasMessageContaining("invariant conflict");
        verify(fixture.storage, never()).uploadIfAbsent(anyString(), any(), anyLong(), anyString());
    }

    @Test
    void matchingObjectAfterCrashCompletesTheStepWithoutUploadingAgain() throws Exception {
        Fixture fixture = fixture(plan("guide.md", "hello"));
        when(fixture.storage.inspect(anyString(), anyLong(), anyString())).thenReturn(matching());

        fixture.workflow.executeForward(fixture.context);

        verify(fixture.storage, never()).uploadIfAbsent(anyString(), any(), anyLong(), anyString());
        verify(fixture.operations).completeStep(eq(fixture.context), startsWith("store-document-"));
    }

    @Test
    void compensationDeletesFinalObjectsBeforeMetadataThenStagingInReverseOrder() throws Exception {
        MarkdownImportPlan plan = plan("a.md", "a", "b.md", "b");
        Fixture fixture = fixture(plan);
        OperationExecutionContext compensation = new OperationExecutionContext(plan.jobId(), UUID.randomUUID(), 1, 0,
                OperationPhase.COMPENSATION);
        when(fixture.steps.findByJobIdAndStepKey(eq(plan.jobId()), startsWith("store-")))
                .thenReturn(Optional.of(OperationStep.builder().status(OperationStepStatus.RUNNING).build()));
        when(fixture.steps.findByJobIdOrderBySequenceNumberAsc(plan.jobId())).thenReturn(List.of());

        fixture.workflow.executeCompensation(compensation);

        var order = inOrder(fixture.storage, fixture.persistence);
        order.verify(fixture.storage).deleteChecked(plan.documents().get(1).objectKey());
        order.verify(fixture.storage).deleteChecked(plan.documents().get(0).objectKey());
        order.verify(fixture.persistence).compensateMetadata(eq(compensation),
                argThat(actual -> actual.toInput().equals(plan.toInput())), eq("cleanup-metadata"));
        order.verify(fixture.storage).deleteChecked(plan.entries().get(1).stagingKey());
        order.verify(fixture.storage).deleteChecked(plan.entries().get(0).stagingKey());
        assertThat(fixture.workflow.completionMode(compensation)).isEqualTo(OperationCompletionMode.RUNNER);
    }

    @Test
    void compensationDeleteOutageIsRetryableAndDoesNotAdvanceCleanupStep() throws Exception {
        MarkdownImportPlan plan = plan("a.md", "a");
        Fixture fixture = fixture(plan);
        OperationExecutionContext compensation = new OperationExecutionContext(plan.jobId(), UUID.randomUUID(), 1, 0,
                OperationPhase.COMPENSATION);
        when(fixture.steps.findByJobIdAndStepKey(eq(plan.jobId()), startsWith("store-")))
                .thenReturn(Optional.of(OperationStep.builder().status(OperationStepStatus.RUNNING).build()));
        doThrow(new IllegalStateException("delete outage")).when(fixture.storage).deleteChecked(anyString());

        assertThatThrownBy(() -> fixture.workflow.executeCompensation(compensation))
                .isInstanceOf(RetryableOperationException.class);
        verify(fixture.operations, never()).completeStep(eq(compensation), startsWith("cleanup-store-"));
    }

    @Test
    void publishTransactionFencesAndAtomicallyCreatesOutboxCommitsQuotaAndCompletesJob() throws Exception {
        MarkdownImportPlan plan = plan("guide.md", "hello");
        MarkdownImportPlan.MarkdownDocument item = plan.documents().get(0);
        OperationExecutionContext context = new OperationExecutionContext(plan.jobId(), UUID.randomUUID(), 2, 0,
                OperationPhase.FORWARD);
        OperationJob operation = OperationJob.builder().id(plan.jobId()).status(OperationStatus.RUNNING)
                .phase(OperationPhase.FORWARD).claimToken(context.claimToken()).claimEpoch(2L).retryEpoch(0L)
                .leaseExpiresAt(Instant.now().plusSeconds(30)).build();
        Document document = Document.builder().id(item.documentId()).kbId(plan.knowledgeBaseId())
                .importJobId(plan.jobId()).objectKey(item.objectKey()).fileName(item.fileName())
                .mimeType("text/markdown").fileSize(item.byteSize()).quotaReservationId(UUID.randomUUID())
                .status(DocumentStatus.IMPORTING).build();
        OperationStep publish = OperationStep.builder().jobId(plan.jobId()).stepKey("publish")
                .status(OperationStepStatus.RUNNING).build();
        OperationDomainGuard guard = mock(OperationDomainGuard.class);
        OperationJobRepository jobs = mock(OperationJobRepository.class);
        OperationStepRepository steps = mock(OperationStepRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentAssetRepository assets = mock(DocumentAssetRepository.class);
        IngestJobRepository ingest = mock(IngestJobRepository.class);
        IngestOutboxEventRepository outbox = mock(IngestOutboxEventRepository.class);
        UploadQuotaService quotas = mock(UploadQuotaService.class);
        KnowledgeBaseRepository knowledgeBases = mock(KnowledgeBaseRepository.class);
        when(guard.assertActive(context)).thenReturn(operation);
        when(documents.findByImportJobIdOrderByCreatedAtAsc(plan.jobId())).thenReturn(List.of(document));
        when(assets.findByDocIdOrderByCreatedAtAsc(item.documentId())).thenReturn(List.of());
        when(steps.findByJobIdAndStepKey(plan.jobId(), "publish")).thenReturn(Optional.of(publish));
        when(ingest.findById(any())).thenReturn(Optional.empty());
        when(ingest.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(outbox.existsById(any())).thenReturn(false);
        KnowledgeBase knowledgeBase = KnowledgeBase.builder().id(plan.knowledgeBaseId()).indexRevision(4L).build();
        when(knowledgeBases.findByIdForUpdate(plan.knowledgeBaseId())).thenReturn(Optional.of(knowledgeBase));
        MarkdownImportPersistenceService persistence = new MarkdownImportPersistenceService(
                guard, jobs, steps, documents, assets, ingest, outbox, quotas, knowledgeBases);

        persistence.publish(context, plan, "publish");

        verify(guard).assertActive(context);
        verify(outbox, times(1)).save(argThat(event -> event.getDocId().equals(item.documentId())));
        verify(quotas).commitForImport(document.getQuotaReservationId(), document.getId());
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.PENDING);
        assertThat(operation.getStatus()).isEqualTo(OperationStatus.COMPLETED);
        assertThat(operation.getClaimToken()).isNull();
        assertThat(publish.getStatus()).isEqualTo(OperationStepStatus.COMPLETED);
        assertThat(knowledgeBase.getIndexRevision()).isEqualTo(5L);
    }

    private static Fixture fixture(MarkdownImportPlan plan) {
        OperationJobRepository jobs = mock(OperationJobRepository.class);
        OperationStepRepository steps = mock(OperationStepRepository.class);
        OperationJobService operations = mock(OperationJobService.class);
        OperationJobClaimService claims = mock(OperationJobClaimService.class);
        MinioStorageService storage = mock(MinioStorageService.class);
        MarkdownImportPersistenceService persistence = mock(MarkdownImportPersistenceService.class);
        OperationExecutionContext context = new OperationExecutionContext(plan.jobId(), UUID.randomUUID(), 1, 0,
                OperationPhase.FORWARD);
        when(jobs.findById(plan.jobId())).thenReturn(Optional.of(OperationJob.builder().id(plan.jobId()).input(plan.toInput()).build()));
        when(operations.recordStep(any(), anyString(), anyString(), anyString())).thenAnswer(invocation ->
                OperationStep.builder().jobId(plan.jobId()).stepKey(invocation.getArgument(1))
                        .status(OperationStepStatus.PENDING).build());
        MarkdownPackageImportWorkflow workflow = new MarkdownPackageImportWorkflow(
                jobs, steps, operations, claims, storage, persistence);
        return new Fixture(plan, context, steps, operations, storage, persistence, workflow);
    }

    private static MarkdownImportPlan plan(String... pathContent) throws Exception {
        UUID jobId = UUID.randomUUID();
        UUID kbId = UUID.randomUUID();
        return new MarkdownPackageParser().parse(jobId, kbId, zip(pathContent).getInputStream());
    }

    private static MockMultipartFile zip(String... entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (int index = 0; index < entries.length; index += 2) {
                zip.putNextEntry(new ZipEntry(entries[index])); zip.write(entries[index + 1].getBytes()); zip.closeEntry();
            }
        }
        return new MockMultipartFile("file", "docs.zip", "application/zip", bytes.toByteArray());
    }

    private static MinioStorageService.ObjectInspection absent() {
        return new MinioStorageService.ObjectInspection(MinioStorageService.ObjectState.ABSENT, 0, null);
    }
    private static MinioStorageService.ObjectInspection matching() {
        return new MinioStorageService.ObjectInspection(MinioStorageService.ObjectState.MATCHING, 0, null);
    }
    private static MinioStorageService.ObjectInspection conflict() {
        return new MinioStorageService.ObjectInspection(MinioStorageService.ObjectState.CONFLICT, 0, null);
    }

    private record Fixture(MarkdownImportPlan plan, OperationExecutionContext context,
                           OperationStepRepository steps, OperationJobService operations, MinioStorageService storage,
                           MarkdownImportPersistenceService persistence,
                           MarkdownPackageImportWorkflow workflow) { }
}
