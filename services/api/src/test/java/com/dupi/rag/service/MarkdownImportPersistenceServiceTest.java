package com.dupi.rag.service;

import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.DocumentAsset;
import com.dupi.rag.domain.entity.OperationJob;
import com.dupi.rag.domain.entity.OperationStep;
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
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MarkdownImportPersistenceServiceTest {

    @TestFactory
    Stream<DynamicTest> publishRejectsEveryMutatedImmutableDocumentField() throws Exception {
        List<NamedDocumentMutation> mutations = List.of(
                mutation("document id", (doc, plan) -> doc.setId(UUID.randomUUID())),
                mutation("knowledge base", (doc, plan) -> doc.setKbId(UUID.randomUUID())),
                mutation("import owner", (doc, plan) -> doc.setImportJobId(UUID.randomUUID())),
                mutation("object key", (doc, plan) -> doc.setObjectKey("wrong/key")),
                mutation("file name", (doc, plan) -> doc.setFileName("wrong.md")),
                mutation("mime type", (doc, plan) -> doc.setMimeType("application/octet-stream")),
                mutation("file size", (doc, plan) -> doc.setFileSize(999L)),
                mutation("status", (doc, plan) -> doc.setStatus(DocumentStatus.PENDING)),
                mutation("quota reservation", (doc, plan) -> doc.setQuotaReservationId(UUID.randomUUID()))
        );
        return mutations.stream().map(mutation -> DynamicTest.dynamicTest(mutation.name(), () -> {
            Fixture fixture = fixture();
            mutation.change().accept(fixture.document, fixture.plan);
            assertInvariantRejectedBeforePublish(fixture);
        }));
    }

    @TestFactory
    Stream<DynamicTest> publishRejectsEveryMutatedImmutableAssetField() throws Exception {
        List<NamedAssetMutation> mutations = List.of(
                assetMutation("asset id", (asset, plan) -> asset.setId(UUID.randomUUID())),
                assetMutation("asset knowledge base", (asset, plan) -> asset.setKbId(UUID.randomUUID())),
                assetMutation("asset document", (asset, plan) -> asset.setDocId(UUID.randomUUID())),
                assetMutation("asset reference", (asset, plan) -> asset.setRelativePath("wrong.png")),
                assetMutation("asset object key", (asset, plan) -> asset.setObjectKey("wrong/key")),
                assetMutation("asset mime", (asset, plan) -> asset.setMimeType("image/jpeg")),
                assetMutation("asset file name", (asset, plan) -> asset.setFileName("wrong.jpg")),
                assetMutation("asset size", (asset, plan) -> asset.setFileSize(999L))
        );
        return mutations.stream().map(mutation -> DynamicTest.dynamicTest(mutation.name(), () -> {
            Fixture fixture = fixture();
            mutation.change().accept(fixture.asset, fixture.plan);
            assertInvariantRejectedBeforePublish(fixture);
        }));
    }

    private static void assertInvariantRejectedBeforePublish(Fixture fixture) {
        assertThatThrownBy(() -> fixture.persistence.publish(fixture.context, fixture.plan, "publish"))
                .isInstanceOf(MarkdownImportInvariantException.class);
        verify(fixture.outbox, never()).save(any());
        verify(fixture.ingest, never()).save(any());
        verify(fixture.quotas, never()).commitForImport(any(), any(), any(), any());
        verify(fixture.jobs, never()).saveAndFlush(any());
        verify(fixture.documents, never()).save(any());
    }

    private static Fixture fixture() throws Exception {
        UUID jobId = UUID.randomUUID();
        UUID kbId = UUID.randomUUID();
        MarkdownImportPlan plan = new MarkdownPackageParser().parse(jobId, kbId, zip(
                "guide.md", "![logo](images/logo.png)", "images/logo.png", "png").getInputStream());
        var plannedDocument = plan.documents().get(0);
        var plannedAsset = plannedDocument.assets().get(0);
        UUID reservationId = MarkdownImportPlan.deterministicId(jobId, "quota", plannedDocument.path());
        Document document = Document.builder().id(plannedDocument.documentId()).kbId(kbId)
                .importJobId(jobId).objectKey(plannedDocument.objectKey()).fileName(plannedDocument.fileName())
                .mimeType("text/markdown").fileSize(plannedDocument.byteSize()).quotaReservationId(reservationId)
                .status(DocumentStatus.IMPORTING).build();
        DocumentAsset asset = DocumentAsset.builder().id(plannedAsset.assetId()).kbId(kbId)
                .docId(plannedDocument.documentId()).relativePath(plannedAsset.reference())
                .objectKey(plannedAsset.objectKey()).mimeType(plannedAsset.mimeType())
                .fileName(plannedAsset.fileName()).fileSize(plannedAsset.byteSize()).build();
        OperationExecutionContext context = new OperationExecutionContext(jobId, UUID.randomUUID(), 1, 0,
                OperationPhase.FORWARD, "tenant-a", "alice");
        OperationJob operation = OperationJob.builder().id(jobId).tenantId("tenant-a").createdBy("alice")
                .status(OperationStatus.RUNNING).phase(OperationPhase.FORWARD).claimToken(context.claimToken())
                .claimEpoch(1L).retryEpoch(0L).leaseExpiresAt(Instant.now().plusSeconds(30)).build();
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
        when(documents.findByImportJobIdOrderByCreatedAtAsc(jobId)).thenReturn(List.of(document));
        when(assets.findByDocIdOrderByCreatedAtAsc(plannedDocument.documentId())).thenReturn(List.of(asset));
        when(steps.findByJobIdAndStepKey(jobId, "publish")).thenReturn(Optional.of(OperationStep.builder()
                .jobId(jobId).stepKey("publish").status(OperationStepStatus.RUNNING).build()));
        MarkdownImportPersistenceService persistence = new MarkdownImportPersistenceService(
                guard, jobs, steps, documents, assets, ingest, outbox, quotas, knowledgeBases);
        return new Fixture(plan, context, document, asset, jobs, documents, ingest, outbox, quotas, persistence);
    }

    private static NamedDocumentMutation mutation(String name, BiConsumer<Document, MarkdownImportPlan> change) {
        return new NamedDocumentMutation(name, change);
    }

    private static NamedAssetMutation assetMutation(String name, BiConsumer<DocumentAsset, MarkdownImportPlan> change) {
        return new NamedAssetMutation(name, change);
    }

    private static MockMultipartFile zip(String... entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (int index = 0; index < entries.length; index += 2) {
                zip.putNextEntry(new ZipEntry(entries[index]));
                zip.write(entries[index + 1].getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return new MockMultipartFile("file", "docs.zip", "application/zip", bytes.toByteArray());
    }

    private record NamedDocumentMutation(String name, BiConsumer<Document, MarkdownImportPlan> change) { }
    private record NamedAssetMutation(String name, BiConsumer<DocumentAsset, MarkdownImportPlan> change) { }
    private record Fixture(MarkdownImportPlan plan, OperationExecutionContext context, Document document,
                           DocumentAsset asset, OperationJobRepository jobs, DocumentRepository documents,
                           IngestJobRepository ingest, IngestOutboxEventRepository outbox, UploadQuotaService quotas,
                           MarkdownImportPersistenceService persistence) { }
}
