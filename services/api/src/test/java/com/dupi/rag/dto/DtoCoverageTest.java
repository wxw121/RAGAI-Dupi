package com.dupi.rag.dto;

import com.dupi.rag.domain.enums.IngestJobStatus;
import com.dupi.rag.domain.enums.IngestStage;
import com.dupi.rag.domain.entity.AuditLog;
import com.dupi.rag.domain.enums.AuditLogStatus;
import com.dupi.rag.domain.enums.ChunkStrategy;
import com.dupi.rag.domain.enums.DocumentStatus;
import com.dupi.rag.domain.enums.RetrievalMode;
import com.dupi.rag.domain.enums.RagEvalRunStatus;
import com.dupi.rag.domain.entity.VectorCleanupTask;
import com.dupi.rag.dto.VectorCleanupTaskResponse;
import com.dupi.rag.domain.enums.VectorCleanupStatus;
import com.dupi.rag.domain.enums.VectorCleanupTargetType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DtoCoverageTest {

    @Test
    void uploadQuotaResponseExposesAllFieldsAndValueSemantics() {
        Instant retryAfter = Instant.parse("2026-07-18T01:00:00Z");
        UploadQuotaResponse first = UploadQuotaResponse.builder()
                .tenantId("tenant-a")
                .userId("alice")
                .retainedBytesUsed(10L)
                .retainedBytesLimit(100L)
                .retainedDocumentsUsed(2L)
                .retainedDocumentsLimit(10L)
                .windowBytesUsed(20L)
                .windowBytesLimit(200L)
                .windowSeconds(60L)
                .retryAfter(retryAfter)
                .build();
        UploadQuotaResponse second = UploadQuotaResponse.builder()
                .tenantId("tenant-a")
                .userId("alice")
                .retainedBytesUsed(10L)
                .retainedBytesLimit(100L)
                .retainedDocumentsUsed(2L)
                .retainedDocumentsLimit(10L)
                .windowBytesUsed(20L)
                .windowBytesLimit(200L)
                .windowSeconds(60L)
                .retryAfter(retryAfter)
                .build();

        assertThat(first.getTenantId()).isEqualTo("tenant-a");
        assertThat(first.getUserId()).isEqualTo("alice");
        assertThat(first.getRetainedBytesUsed()).isEqualTo(10L);
        assertThat(first.getRetainedBytesLimit()).isEqualTo(100L);
        assertThat(first.getRetainedDocumentsUsed()).isEqualTo(2L);
        assertThat(first.getRetainedDocumentsLimit()).isEqualTo(10L);
        assertThat(first.getWindowBytesUsed()).isEqualTo(20L);
        assertThat(first.getWindowBytesLimit()).isEqualTo(200L);
        assertThat(first.getWindowSeconds()).isEqualTo(60L);
        assertThat(first.getRetryAfter()).isEqualTo(retryAfter);
        assertThat(first).isEqualTo(second);
        assertThat(first.hashCode()).isEqualTo(second.hashCode());
        assertThat(first.toString()).contains("tenant-a", "retainedBytesLimit=100");

        second.setTenantId("tenant-b");
        second.setUserId("bob");
        second.setRetainedBytesUsed(11L);
        second.setRetainedBytesLimit(101L);
        second.setRetainedDocumentsUsed(3L);
        second.setRetainedDocumentsLimit(11L);
        second.setWindowBytesUsed(21L);
        second.setWindowBytesLimit(201L);
        second.setWindowSeconds(61L);
        second.setRetryAfter(null);
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void responseDtosExposeAllBuilderFields() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();

        KnowledgeBaseResponse kb = KnowledgeBaseResponse.builder()
                .id(id).tenantId("t").name("n").description("d")
                .chunkSize(1).chunkOverlap(2).topK(3)
                .embeddingModel("m").embeddingDimension(4)
                .chunkStrategy(ChunkStrategy.MARKDOWN).retrievalMode(RetrievalMode.HYBRID)
                .createdAt(now).updatedAt(now).build();
        assertThat(kb.getId()).isEqualTo(id);
        assertThat(kb.getTenantId()).isEqualTo("t");
        assertThat(kb.getName()).isEqualTo("n");
        assertThat(kb.getChunkSize()).isEqualTo(1);
        assertThat(kb.getChunkOverlap()).isEqualTo(2);
        assertThat(kb.getTopK()).isEqualTo(3);
        assertThat(kb.getEmbeddingModel()).isEqualTo("m");
        assertThat(kb.getEmbeddingDimension()).isEqualTo(4);
        assertThat(kb.getChunkStrategy()).isEqualTo(ChunkStrategy.MARKDOWN);
        assertThat(kb.getRetrievalMode()).isEqualTo(RetrievalMode.HYBRID);
        assertThat(kb.getCreatedAt()).isEqualTo(now);
        assertThat(kb.getDescription()).isEqualTo("d");
        assertThat(kb.getUpdatedAt()).isEqualTo(now);

        DocumentResponse doc = DocumentResponse.builder()
                .id(id).kbId(id).fileName("f").mimeType("text").fileSize(9L)
                .status(DocumentStatus.COMPLETED).errorMessage("e").createdAt(now).updatedAt(now).build();
        assertThat(doc.getId()).isEqualTo(id);
        assertThat(doc.getKbId()).isEqualTo(id);
        assertThat(doc.getFileName()).isEqualTo("f");
        assertThat(doc.getMimeType()).isEqualTo("text");
        assertThat(doc.getFileSize()).isEqualTo(9L);
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(doc.getErrorMessage()).isEqualTo("e");
        assertThat(doc.getCreatedAt()).isEqualTo(now);
        assertThat(doc.getUpdatedAt()).isEqualTo(now);

        IngestJobResponse job = IngestJobResponse.builder()
                .id(id).kbId(id).docId(id).status(IngestJobStatus.PENDING)
                .stage(IngestStage.QUEUED).retryCount(2).errorMessage("e")
                .createdAt(now).updatedAt(now).build();
        assertThat(job.getId()).isEqualTo(id);
        assertThat(job.getKbId()).isEqualTo(id);
        assertThat(job.getDocId()).isEqualTo(id);
        assertThat(job.getStatus()).isEqualTo(IngestJobStatus.PENDING);
        assertThat(job.getRetryCount()).isEqualTo(2);
        assertThat(job.getStage()).isEqualTo(IngestStage.QUEUED);
        assertThat(job.getErrorMessage()).isEqualTo("e");
        assertThat(job.getCreatedAt()).isEqualTo(now);
        assertThat(job.getUpdatedAt()).isEqualTo(now);

        RetrieveResponse retrieve = RetrieveResponse.builder().query("q").retrievalMode("vector").hits(java.util.List.of()).build();
        assertThat(retrieve.getQuery()).isEqualTo("q");
        assertThat(retrieve.getHits()).isEmpty();
        assertThat(retrieve.getRetrievalMode()).isEqualTo("vector");

        Citation citation = Citation.builder().chunkId(id).docId(id).fileName("f").snippet("s").score(0.5).build();
        assertThat(citation.getChunkId()).isEqualTo(id);
        assertThat(citation.getDocId()).isEqualTo(id);
        assertThat(citation.getFileName()).isEqualTo("f");
        assertThat(citation.getSnippet()).isEqualTo("s");
        assertThat(citation.getScore()).isEqualTo(0.5);

        RetrievalHit hit = RetrievalHit.builder().chunkId(id).docId(id).fileName("f").content("c").score(1.0).metadata(Map.of("k", "v")).build();
        assertThat(hit.getChunkId()).isEqualTo(id);
        assertThat(hit.getDocId()).isEqualTo(id);
        assertThat(hit.getFileName()).isEqualTo("f");
        assertThat(hit.getContent()).isEqualTo("c");
        assertThat(hit.getScore()).isEqualTo(1.0);
        assertThat(hit.getMetadata()).containsEntry("k", "v");

        ChatSessionResponse session = ChatSessionResponse.builder()
                .id(id).kbId(id).title("chat")
                .createdAt(now).updatedAt(now).build();
        assertThat(session.getId()).isEqualTo(id);
        assertThat(session.getKbId()).isEqualTo(id);
        assertThat(session.getTitle()).isEqualTo("chat");
        assertThat(session.getCreatedAt()).isEqualTo(now);
        assertThat(session.getUpdatedAt()).isEqualTo(now);

        ChatMessageResponse message = ChatMessageResponse.builder()
                .id(id).sessionId(id).sequenceNumber(1).role("USER").content("hello")
                .citations(List.of(citation)).createdAt(now).build();
        assertThat(message.getId()).isEqualTo(id);
        assertThat(message.getSessionId()).isEqualTo(id);
        assertThat(message.getSequenceNumber()).isEqualTo(1);
        assertThat(message.getRole()).isEqualTo("USER");
        assertThat(message.getContent()).isEqualTo("hello");
        assertThat(message.getCitations()).containsExactly(citation);
        assertThat(message.getCreatedAt()).isEqualTo(now);

        ChatSessionDetailResponse detail = ChatSessionDetailResponse.builder()
                .session(session).messages(List.of(message)).build();
        assertThat(detail.getSession()).isSameAs(session);
        assertThat(detail.getMessages()).containsExactly(message);

        BatchDocumentUploadResult uploadResult = BatchDocumentUploadResult.builder()
                .fileName("f")
                .success(true)
                .document(doc)
                .build();
        BatchDocumentUploadResponse batchUpload = BatchDocumentUploadResponse.builder()
                .total(1)
                .succeeded(1)
                .failed(0)
                .results(List.of(uploadResult))
                .build();
        assertThat(batchUpload.getTotal()).isEqualTo(1);
        assertThat(batchUpload.getSucceeded()).isEqualTo(1);
        assertThat(batchUpload.getFailed()).isZero();
        assertThat(batchUpload.getResults()).containsExactly(uploadResult);
        assertThat(uploadResult.getDocument()).isSameAs(doc);

        VectorCleanupTaskResponse cleanupTask = VectorCleanupTaskResponse.builder()
                .id(id)
                .targetType(VectorCleanupTargetType.KNOWLEDGE_BASE)
                .targetId(id)
                .status(VectorCleanupStatus.FAILED)
                .attemptCount(3)
                .lastError("milvus unavailable")
                .nextAttemptAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
        assertThat(cleanupTask.getId()).isEqualTo(id);
        assertThat(cleanupTask.getTargetType()).isEqualTo(VectorCleanupTargetType.KNOWLEDGE_BASE);
        assertThat(cleanupTask.getTargetId()).isEqualTo(id);
        assertThat(cleanupTask.getStatus()).isEqualTo(VectorCleanupStatus.FAILED);
        assertThat(cleanupTask.getAttemptCount()).isEqualTo(3);
        assertThat(cleanupTask.getLastError()).isEqualTo("milvus unavailable");
        assertThat(cleanupTask.getNextAttemptAt()).isEqualTo(now);
        assertThat(cleanupTask.getCreatedAt()).isEqualTo(now);
        assertThat(cleanupTask.getUpdatedAt()).isEqualTo(now);

        AuditLog auditLog = AuditLog.builder()
                .id(id)
                .tenantId("tenant-a")
                .action("DOCUMENT_DELETE")
                .targetType("DOCUMENT")
                .targetId(id)
                .status(AuditLogStatus.FAILED)
                .message("m")
                .errorMessage("e")
                .createdAt(now)
                .build();
        AuditLogResponse auditResponse = AuditLogResponse.from(auditLog);
        assertThat(auditResponse.getId()).isEqualTo(id);
        assertThat(auditResponse.getTenantId()).isEqualTo("tenant-a");
        assertThat(auditResponse.getAction()).isEqualTo("DOCUMENT_DELETE");
        assertThat(auditResponse.getTargetType()).isEqualTo("DOCUMENT");
        assertThat(auditResponse.getTargetId()).isEqualTo(id);
        assertThat(auditResponse.getStatus()).isEqualTo(AuditLogStatus.FAILED);
        assertThat(auditResponse.getMessage()).isEqualTo("m");
        assertThat(auditResponse.getErrorMessage()).isEqualTo("e");
        assertThat(auditResponse.getCreatedAt()).isEqualTo(now);

        AuditAlertResponse auditAlert = AuditAlertResponse.builder()
                .code("AUDIT_FAILED_SPIKE")
                .severity("WARN")
                .message("failed audit spike")
                .count(11)
                .threshold(10)
                .windowStart(now.minusSeconds(60))
                .windowEnd(now)
                .build();
        assertThat(auditAlert.getCode()).isEqualTo("AUDIT_FAILED_SPIKE");
        assertThat(auditAlert.getSeverity()).isEqualTo("WARN");
        assertThat(auditAlert.getMessage()).isEqualTo("failed audit spike");
        assertThat(auditAlert.getCount()).isEqualTo(11);
        assertThat(auditAlert.getThreshold()).isEqualTo(10);
        assertThat(auditAlert.getWindowStart()).isEqualTo(now.minusSeconds(60));
        assertThat(auditAlert.getWindowEnd()).isEqualTo(now);

        PermissionMetadataResponse permission = PermissionMetadataResponse.builder()
                .code("KB_READ")
                .name("Read")
                .description("read knowledge bases")
                .allows(List.of("chat"))
                .denies(List.of("delete"))
                .build();
        OpsMetadataResponse metadata = OpsMetadataResponse.builder()
                .permissions(List.of("KB_READ", "CHAT_WRITE"))
                .permissionDetails(List.of(permission))
                .auditActions(List.of("LOGIN", "DOCUMENT_UPLOAD"))
                .auditTargetTypes(List.of("USER", "DOCUMENT"))
                .auditStatuses(List.of("SUCCESS", "FAILED"))
                .guardrails(OpsGuardrailsResponse.builder()
                        .uploadRateLimit(OpsGuardrailsResponse.UploadRateLimit.builder()
                                .enabled(true)
                                .requests(10)
                                .windowSeconds(60)
                                .build())
                        .ingestQueue(OpsGuardrailsResponse.IngestQueue.builder()
                                .maxPendingJobs(5)
                                .maxRecoveryAttempts(3)
                                .build())
                        .audit(OpsGuardrailsResponse.Audit.builder()
                                .alertWindowMinutes(15)
                                .alertFailedThreshold(2)
                                .build())
                        .multipart(OpsGuardrailsResponse.Multipart.builder()
                                .maxFileSizeBytes(10_485_760L)
                                .build())
                        .build())
                .build();
        assertThat(metadata.getPermissions()).containsExactly("KB_READ", "CHAT_WRITE");
        assertThat(metadata.getPermissionDetails()).containsExactly(permission);
        assertThat(metadata.getAuditActions()).containsExactly("LOGIN", "DOCUMENT_UPLOAD");
        assertThat(metadata.getAuditTargetTypes()).containsExactly("USER", "DOCUMENT");
        assertThat(metadata.getAuditStatuses()).containsExactly("SUCCESS", "FAILED");
        assertThat(metadata.getGuardrails().getUploadRateLimit().isEnabled()).isTrue();
        assertThat(metadata.getGuardrails().getIngestQueue().getMaxPendingJobs()).isEqualTo(5);
        assertThat(metadata.getGuardrails().getAudit().getAlertFailedThreshold()).isEqualTo(2);
        assertThat(metadata.getGuardrails().getMultipart().getMaxFileSizeBytes()).isEqualTo(10_485_760L);
    }

    @Test
    void auditLogQueryExposesFilters() {
        AuditLogQuery query = new AuditLogQuery();
        query.setTenantId("tenant-a");
        query.setAction("REINDEX");
        query.setTargetType("KNOWLEDGE_BASE");
        query.setStatus(AuditLogStatus.SUCCESS);
        query.setLimit(25);

        assertThat(query.getTenantId()).isEqualTo("tenant-a");
        assertThat(query.getAction()).isEqualTo("REINDEX");
        assertThat(query.getTargetType()).isEqualTo("KNOWLEDGE_BASE");
        assertThat(query.getStatus()).isEqualTo(AuditLogStatus.SUCCESS);
        assertThat(query.getLimit()).isEqualTo(25);
    }

    @Test
    void ingestStatusUpdateAndChunkPayloadExposeMutators() {
        IngestStatusUpdate update = new IngestStatusUpdate();
        update.setJobId("j");
        update.setDocId("d");
        update.setStatus("completed");
        update.setStage("indexing");
        update.setErrorMessage("none");
        update.setMilvusIds(java.util.List.of("m1"));
        IngestStatusUpdate.ChunkPayload payload = new IngestStatusUpdate.ChunkPayload();
        payload.setId("c");
        payload.setChunkIndex(1);
        payload.setContent("content");
        payload.setTokenCount(2);
        payload.setMetadata(Map.of("h", "H"));
        payload.setMilvusId("m1");
        update.setChunks(java.util.List.of(payload));

        assertThat(update.getJobId()).isEqualTo("j");
        assertThat(update.getMilvusIds()).containsExactly("m1");
        assertThat(update.getChunks().get(0).getMetadata()).containsEntry("h", "H");
    }

    @Test
    void chatSessionRequestsExposeMutators() {
        UUID sessionId = UUID.randomUUID();

        CreateChatSessionRequest create = new CreateChatSessionRequest();
        create.setTitle("new chat");
        assertThat(create.getTitle()).isEqualTo("new chat");

        UpdateChatSessionRequest update = new UpdateChatSessionRequest();
        update.setTitle("renamed chat");
        assertThat(update.getTitle()).isEqualTo("renamed chat");

        BatchDeleteChatSessionsRequest batchDelete = new BatchDeleteChatSessionsRequest();
        batchDelete.setSessionIds(List.of(sessionId));
        assertThat(batchDelete.getSessionIds()).containsExactly(sessionId);
    }

    @Test
    void vectorCleanupTaskEntityExposesStateFields() {
        UUID targetId = UUID.randomUUID();
        Instant now = Instant.now();
        VectorCleanupTask task = VectorCleanupTask.builder()
                .id(UUID.randomUUID())
                .targetType(VectorCleanupTargetType.DOCUMENT)
                .targetId(targetId)
                .status(VectorCleanupStatus.PENDING)
                .attemptCount(2)
                .lastError("milvus")
                .nextAttemptAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();

        assertThat(task.getTargetType()).isEqualTo(VectorCleanupTargetType.DOCUMENT);
        assertThat(task.getTargetId()).isEqualTo(targetId);
        assertThat(task.getStatus()).isEqualTo(VectorCleanupStatus.PENDING);
        assertThat(task.getAttemptCount()).isEqualTo(2);
        assertThat(task.getLastError()).isEqualTo("milvus");
        assertThat(task.getId()).isNotNull();
        assertThat(task.getNextAttemptAt()).isEqualTo(now);
        assertThat(task.getCreatedAt()).isEqualTo(now);
        assertThat(task.getUpdatedAt()).isEqualTo(now);
        assertThat(VectorCleanupTargetType.KNOWLEDGE_BASE).isNotNull();
        assertThat(VectorCleanupStatus.COMPLETED).isNotNull();
        assertThat(VectorCleanupStatus.FAILED).isNotNull();
    }

    @Test
    void qualityLoopDtosExposeCompleteApiContracts() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();

        RagEvalCaseResponse evalCase = new RagEvalCaseResponse();
        evalCase.setId(id);
        evalCase.setKbId(id);
        evalCase.setCaseKey("case-1");
        evalCase.setQuery("query");
        evalCase.setMinHits(1);
        evalCase.setTopK(5);
        evalCase.setExpectedFileName("guide.md");
        evalCase.setMustContainAny(List.of("token"));
        evalCase.setCreatedAt(now);
        evalCase.setUpdatedAt(now);
        assertThat(evalCase.getId()).isEqualTo(id);
        assertThat(evalCase.getKbId()).isEqualTo(id);
        assertThat(evalCase.getCaseKey()).isEqualTo("case-1");
        assertThat(evalCase.getQuery()).isEqualTo("query");
        assertThat(evalCase.getMinHits()).isEqualTo(1);
        assertThat(evalCase.getTopK()).isEqualTo(5);
        assertThat(evalCase.getExpectedFileName()).isEqualTo("guide.md");
        assertThat(evalCase.getMustContainAny()).containsExactly("token");
        assertThat(evalCase.getCreatedAt()).isEqualTo(now);
        assertThat(evalCase.getUpdatedAt()).isEqualTo(now);

        RagEvalRunResultResponse result = new RagEvalRunResultResponse();
        result.setId(id);
        result.setCaseId(id);
        result.setCaseKey("case-1");
        result.setQuery("query");
        result.setPassed(true);
        result.setFailureReasons(List.of());
        result.setHitCount(2);
        result.setExpectedFileName("guide.md");
        result.setMatchedFileName("guide.md");
        result.setMatchedToken("token");
        result.setRetrievalMode("hybrid_rerank");
        result.setFallbackReason("none");
        result.setEmbeddingModel("bge-m3");
        result.setEmbeddingDimension(1024);
        result.setTopK(5);
        assertThat(result.getId()).isEqualTo(id);
        assertThat(result.getCaseId()).isEqualTo(id);
        assertThat(result.getCaseKey()).isEqualTo("case-1");
        assertThat(result.getQuery()).isEqualTo("query");
        assertThat(result.isPassed()).isTrue();
        assertThat(result.getFailureReasons()).isEmpty();
        assertThat(result.getHitCount()).isEqualTo(2);
        assertThat(result.getExpectedFileName()).isEqualTo("guide.md");
        assertThat(result.getMatchedFileName()).isEqualTo("guide.md");
        assertThat(result.getMatchedToken()).isEqualTo("token");
        assertThat(result.getRetrievalMode()).isEqualTo("hybrid_rerank");
        assertThat(result.getFallbackReason()).isEqualTo("none");
        assertThat(result.getEmbeddingModel()).isEqualTo("bge-m3");
        assertThat(result.getEmbeddingDimension()).isEqualTo(1024);
        assertThat(result.getTopK()).isEqualTo(5);

        RagEvalRunResponse run = new RagEvalRunResponse();
        run.setId(id);
        run.setKbId(id);
        run.setUseRerank(true);
        run.setPassedCount(1);
        run.setTotalCount(1);
        run.setStatus(RagEvalRunStatus.FAILED);
        run.setFailureMessage("provider down");
        run.setCreatedAt(now);
        run.setResults(List.of(result));
        assertThat(run.getId()).isEqualTo(id);
        assertThat(run.getKbId()).isEqualTo(id);
        assertThat(run.isUseRerank()).isTrue();
        assertThat(run.getPassedCount()).isEqualTo(1);
        assertThat(run.getTotalCount()).isEqualTo(1);
        assertThat(run.getStatus()).isEqualTo(RagEvalRunStatus.FAILED);
        assertThat(run.getFailureMessage()).isEqualTo("provider down");
        assertThat(run.getCreatedAt()).isEqualTo(now);
        assertThat(run.getResults()).containsExactly(result);

        DocumentResponse document = DocumentResponse.builder().id(id).fileName("guide.md").build();
        IngestJobResponse job = IngestJobResponse.builder().id(id).build();
        DocumentIndexDetailResponse.ChunkPreview preview = new DocumentIndexDetailResponse.ChunkPreview();
        preview.setId(id);
        preview.setChunkIndex(0);
        preview.setContentPreview("preview");
        preview.setTokenCount(3);
        preview.setMetadata(Map.of("heading", "Intro"));
        preview.setMilvusId("milvus-1");
        assertThat(preview.getId()).isEqualTo(id);
        assertThat(preview.getChunkIndex()).isZero();
        assertThat(preview.getContentPreview()).isEqualTo("preview");
        assertThat(preview.getTokenCount()).isEqualTo(3);
        assertThat(preview.getMetadata()).containsEntry("heading", "Intro");
        assertThat(preview.getMilvusId()).isEqualTo("milvus-1");

        DocumentIndexDetailResponse indexDetail = new DocumentIndexDetailResponse();
        indexDetail.setDocument(document);
        indexDetail.setLatestJob(job);
        indexDetail.setObjectKey("kb/guide.md");
        indexDetail.setObjectAvailable(true);
        indexDetail.setIndexReady(true);
        indexDetail.setChunkCount(1);
        indexDetail.setChunks(List.of(preview));
        assertThat(indexDetail.getDocument()).isSameAs(document);
        assertThat(indexDetail.getLatestJob()).isSameAs(job);
        assertThat(indexDetail.getObjectKey()).isEqualTo("kb/guide.md");
        assertThat(indexDetail.isObjectAvailable()).isTrue();
        assertThat(indexDetail.isIndexReady()).isTrue();
        assertThat(indexDetail.getChunkCount()).isEqualTo(1);
        assertThat(indexDetail.getChunks()).containsExactly(preview);

        KnowledgeBaseExportResponse.KnowledgeBaseSnapshot kbSnapshot = new KnowledgeBaseExportResponse.KnowledgeBaseSnapshot();
        kbSnapshot.setOriginalId(id);
        kbSnapshot.setTenantId("tenant-a");
        kbSnapshot.setName("KB");
        kbSnapshot.setDescription("desc");
        kbSnapshot.setChunkSize(512);
        kbSnapshot.setChunkOverlap(64);
        kbSnapshot.setTopK(5);
        kbSnapshot.setEmbeddingModel("bge-m3");
        kbSnapshot.setEmbeddingDimension(1024);
        kbSnapshot.setChunkStrategy(ChunkStrategy.MARKDOWN);
        kbSnapshot.setRetrievalMode(RetrievalMode.HYBRID);
        assertThat(kbSnapshot.getOriginalId()).isEqualTo(id);
        assertThat(kbSnapshot.getTenantId()).isEqualTo("tenant-a");
        assertThat(kbSnapshot.getName()).isEqualTo("KB");
        assertThat(kbSnapshot.getDescription()).isEqualTo("desc");
        assertThat(kbSnapshot.getChunkSize()).isEqualTo(512);
        assertThat(kbSnapshot.getChunkOverlap()).isEqualTo(64);
        assertThat(kbSnapshot.getTopK()).isEqualTo(5);
        assertThat(kbSnapshot.getEmbeddingModel()).isEqualTo("bge-m3");
        assertThat(kbSnapshot.getEmbeddingDimension()).isEqualTo(1024);
        assertThat(kbSnapshot.getChunkStrategy()).isEqualTo(ChunkStrategy.MARKDOWN);
        assertThat(kbSnapshot.getRetrievalMode()).isEqualTo(RetrievalMode.HYBRID);

        KnowledgeBaseExportResponse.DocumentSnapshot docSnapshot = new KnowledgeBaseExportResponse.DocumentSnapshot();
        docSnapshot.setOriginalId(id);
        docSnapshot.setFileName("guide.md");
        docSnapshot.setObjectKey("kb/guide.md");
        docSnapshot.setMimeType("text/markdown");
        docSnapshot.setFileSize(42L);
        docSnapshot.setStatus(DocumentStatus.COMPLETED);
        docSnapshot.setErrorMessage("none");
        assertThat(docSnapshot.getOriginalId()).isEqualTo(id);
        assertThat(docSnapshot.getFileName()).isEqualTo("guide.md");
        assertThat(docSnapshot.getObjectKey()).isEqualTo("kb/guide.md");
        assertThat(docSnapshot.getMimeType()).isEqualTo("text/markdown");
        assertThat(docSnapshot.getFileSize()).isEqualTo(42L);
        assertThat(docSnapshot.getStatus()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(docSnapshot.getErrorMessage()).isEqualTo("none");

        KnowledgeBaseExportResponse.ChunkSnapshot chunkSnapshot = new KnowledgeBaseExportResponse.ChunkSnapshot();
        chunkSnapshot.setOriginalId(id);
        chunkSnapshot.setOriginalDocId(id);
        chunkSnapshot.setChunkIndex(0);
        chunkSnapshot.setContent("content");
        chunkSnapshot.setTokenCount(1);
        chunkSnapshot.setMetadata(Map.of("source", "guide"));
        chunkSnapshot.setMilvusId("milvus-1");
        assertThat(chunkSnapshot.getOriginalId()).isEqualTo(id);
        assertThat(chunkSnapshot.getOriginalDocId()).isEqualTo(id);
        assertThat(chunkSnapshot.getChunkIndex()).isZero();
        assertThat(chunkSnapshot.getContent()).isEqualTo("content");
        assertThat(chunkSnapshot.getTokenCount()).isEqualTo(1);
        assertThat(chunkSnapshot.getMetadata()).containsEntry("source", "guide");
        assertThat(chunkSnapshot.getMilvusId()).isEqualTo("milvus-1");

        KnowledgeBaseExportResponse export = new KnowledgeBaseExportResponse();
        export.setKnowledgeBase(kbSnapshot);
        export.setDocuments(List.of(docSnapshot));
        export.setChunks(List.of(chunkSnapshot));
        export.setEvalCases(List.of(evalCase));
        export.setExportedAt(now);
        assertThat(export.getKnowledgeBase()).isSameAs(kbSnapshot);
        assertThat(export.getDocuments()).containsExactly(docSnapshot);
        assertThat(export.getChunks()).containsExactly(chunkSnapshot);
        assertThat(export.getEvalCases()).containsExactly(evalCase);
        assertThat(export.getExportedAt()).isEqualTo(now);

        OpsNotificationResponse notification = new OpsNotificationResponse();
        notification.setConfigured(true);
        notification.setDelivered(true);
        notification.setAlertCount(2);
        notification.setStatusCode(202);
        notification.setMessage("delivered");
        assertThat(notification.isConfigured()).isTrue();
        assertThat(notification.isDelivered()).isTrue();
        assertThat(notification.getAlertCount()).isEqualTo(2);
        assertThat(notification.getStatusCode()).isEqualTo(202);
        assertThat(notification.getMessage()).isEqualTo("delivered");

        ApiErrorResponse error = ApiErrorResponse.of("chat_failed", "provider unavailable", "llm", "retry", "req-1");
        assertThat(error.getError()).isEqualTo("chat_failed");
        assertThat(error.getMessage()).isEqualTo("provider unavailable");
        assertThat(error.getStage()).isEqualTo("llm");
        assertThat(error.getSuggestion()).isEqualTo("retry");
        assertThat(error.getRequestId()).isEqualTo("req-1");
        assertThat(error.getTimestamp()).isNotBlank();

        ApiErrorResponse mutableError = new ApiErrorResponse();
        mutableError.setError("bad_request");
        assertThat(mutableError.getError()).isEqualTo("bad_request");
    }
}
