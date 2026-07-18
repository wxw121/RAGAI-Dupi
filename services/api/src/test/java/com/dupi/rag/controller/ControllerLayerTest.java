package com.dupi.rag.controller;

import com.dupi.rag.domain.entity.Chunk;
import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.enums.IngestJobStatus;
import com.dupi.rag.config.ApiSecurityProperties;
import com.dupi.rag.config.ApiTokenService;
import com.dupi.rag.config.AuditProperties;
import com.dupi.rag.config.RedisQueueProperties;
import com.dupi.rag.config.UploadRateLimitProperties;
import com.dupi.rag.dto.*;
import com.dupi.rag.repository.ChunkRepository;
import com.dupi.rag.repository.DocumentRepository;
import com.dupi.rag.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ControllerLayerTest {

    @Test
    void authControllerIssuesSignedTokenAndRejectsInvalidCredentials() {
        ApiSecurityProperties properties = new ApiSecurityProperties();
        properties.setAuthSecret("test-secret");
        properties.setTokenTtlSeconds(60);
        properties.getUsers().add(user("admin", "pw", "ops", "ADMIN"));
        ApiTokenService tokenService = new ApiTokenService(
                properties,
                Clock.fixed(Instant.parse("2026-07-06T00:00:00Z"), ZoneOffset.UTC)
        );
        AuthController controller = new AuthController(tokenService);
        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("pw");

        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        LoginResponse response = controller.login(request, servletResponse);

        assertThat(response.getUsername()).isEqualTo("admin");
        assertThat(response.getTenantId()).isEqualTo("ops");
        assertThat(response.getRole()).isEqualTo("ADMIN");
        assertThat(response.getExpiresAt()).isEqualTo(Instant.parse("2026-07-06T00:01:00Z"));
        assertThat(response.getToken()).isNull();
        assertThat(response.getCsrfToken()).isNotBlank();
        assertThat(servletResponse.getHeaders("Set-Cookie")).anySatisfy(cookie -> {
            assertThat(cookie).contains("DUPI_AUTH=");
            assertThat(cookie).contains("HttpOnly");
            assertThat(cookie).contains("SameSite=Lax");
        }).anySatisfy(cookie -> {
            assertThat(cookie).contains("DUPI_CSRF=");
            assertThat(cookie).contains("SameSite=Lax");
            assertThat(cookie).doesNotContain("HttpOnly");
        });

        LoginRequest invalid = new LoginRequest();
        invalid.setUsername("admin");
        invalid.setPassword("bad");
        assertThatThrownBy(() -> controller.login(invalid, new MockHttpServletResponse()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void authControllerReturnsServiceUnavailableWhenAuthSecretIsMissing() {
        ApiSecurityProperties properties = new ApiSecurityProperties();
        properties.getUsers().add(user("admin", "pw", "ops", "ADMIN"));
        ApiTokenService tokenService = new ApiTokenService(
                properties,
                Clock.fixed(Instant.parse("2026-07-06T00:00:00Z"), ZoneOffset.UTC)
        );
        AuthController controller = new AuthController(tokenService);
        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("pw");

        assertThatThrownBy(() -> controller.login(request, new MockHttpServletResponse()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void documentControllerDelegatesAllRoutesAndValidatesIngestJobOwnership() {
        DocumentService documentService = mock(DocumentService.class);
        IngestJobService ingestJobService = mock(IngestJobService.class);
        DocumentIndexInspectionService documentIndexInspectionService = mock(DocumentIndexInspectionService.class);
        DocumentController controller = new DocumentController(documentService, ingestJobService, documentIndexInspectionService);
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        MockMultipartFile file = new MockMultipartFile("file", "a.txt", "text/plain", "x".getBytes());
        MockMultipartFile batchFile = new MockMultipartFile("files", "b.txt", "text/plain", "y".getBytes());
        DocumentResponse docResponse = DocumentResponse.builder().id(docId).kbId(kbId).fileName("a.txt").build();
        DocumentResponse batchResponse = DocumentResponse.builder().id(UUID.randomUUID()).kbId(kbId).fileName("b.txt").build();
        BatchDocumentUploadResponse batchUploadResponse = BatchDocumentUploadResponse.builder()
                .total(1)
                .succeeded(1)
                .failed(0)
                .results(List.of(BatchDocumentUploadResult.builder()
                        .fileName("b.txt")
                        .success(true)
                        .document(batchResponse)
                        .build()))
                .build();
        IngestJobResponse jobResponse = IngestJobResponse.builder().docId(docId).build();
        DocumentIndexDetailResponse detailResponse = DocumentIndexDetailResponse.builder()
                .document(docResponse)
                .objectKey("key")
                .build();
        when(documentService.upload(kbId, file, null)).thenReturn(docResponse);
        when(documentService.uploadBatch(kbId, List.of(batchFile))).thenReturn(batchUploadResponse);
        when(documentService.listByKb(kbId)).thenReturn(List.of(docResponse));
        when(documentService.get(kbId, docId)).thenReturn(docResponse);
        when(ingestJobService.getLatestByDoc(docId)).thenReturn(jobResponse);
        when(documentIndexInspectionService.inspect(kbId, docId)).thenReturn(detailResponse);

        assertThat(controller.upload(kbId, file)).isSameAs(docResponse);
        assertThat(controller.uploadBatch(kbId, List.of(batchFile))).isSameAs(batchUploadResponse);
        assertThat(controller.list(kbId)).containsExactly(docResponse);
        assertThat(controller.get(kbId, docId)).isSameAs(docResponse);
        controller.delete(kbId, docId);
        assertThat(controller.getIngestJob(kbId, docId)).isSameAs(jobResponse);
        assertThat(controller.getIndexDetail(kbId, docId)).isSameAs(detailResponse);

        verify(documentService).findOrThrow(kbId, docId);
        verify(documentService).delete(kbId, docId);
    }

    @Test
    void ingestCallbackControllerDelegatesStatusAndRetry() {
        IngestJobService service = mock(IngestJobService.class);
        IngestCallbackController controller = new IngestCallbackController(service);
        UUID jobId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        IngestStatusUpdate update = IngestStatusUpdate.builder().jobId(jobId.toString()).build();
        IngestJobResponse response = IngestJobResponse.builder().id(jobId).build();
        when(service.handleStatusUpdate(update)).thenReturn(IngestCallbackAckResponse.ok());
        when(service.claim(jobId, executionId, "worker-a", java.time.Duration.ofSeconds(45))).thenReturn(response);
        when(service.refreshLease(jobId, executionId, "worker-a", java.time.Duration.ofSeconds(30))).thenReturn(response);
        when(service.isCancellationRequested(jobId, executionId)).thenReturn(true);
        Map<String, Object> executionState = Map.of(
                "status", IngestJobStatus.PROCESSING,
                "executionCurrent", true,
                "terminal", false,
                "leaseExpired", false,
                "requeueEligible", false
        );
        when(service.getExecutionState(jobId, executionId)).thenReturn(executionState);
        when(service.retry(jobId)).thenReturn(response);

        assertThat(controller.updateStatus(update)).containsEntry("status", "ok");
        assertThat(controller.claim(jobId, Map.of(
                "executionId", executionId.toString(),
                "workerId", "worker-a",
                "leaseSeconds", 45
        ))).isSameAs(response);
        assertThat(controller.refreshLease(jobId, Map.of(
                "executionId", executionId.toString(),
                "workerId", "worker-a",
                "leaseSeconds", 30
        ))).isSameAs(response);
        assertThat(controller.cancelled(jobId, executionId)).containsEntry("cancelled", true);
        assertThat(controller.executionState(jobId, executionId)).isSameAs(executionState);
        assertThat(controller.retry(jobId)).isSameAs(response);
        verify(service).handleStatusUpdate(update);
        verify(service).getExecutionState(jobId, executionId);
    }

    @Test
    void ingestCallbackControllerExposesExecutionStateContract() {
        assertThatCode(() -> {
            var method = IngestCallbackController.class.getMethod(
                    "executionState", UUID.class, UUID.class);
            var mapping = method.getAnnotation(GetMapping.class);
            assertThat(mapping).isNotNull();
            assertThat(mapping.value())
                    .containsExactly("/jobs/{jobId}/executions/{executionId}/state");
        }).doesNotThrowAnyException();
    }

    @Test
    void knowledgeBaseControllerDelegatesCrudRetrieveChatCancelAndJobs() {
        KnowledgeBaseService kbService = mock(KnowledgeBaseService.class);
        RetrievalService retrievalService = mock(RetrievalService.class);
        ChatService chatService = mock(ChatService.class);
        IngestJobService ingestJobService = mock(IngestJobService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        RagEvalService ragEvalService = mock(RagEvalService.class);
        KnowledgeBaseExportService knowledgeBaseExportService = mock(KnowledgeBaseExportService.class);
        RetrievalProfileService retrievalProfileService = mock(RetrievalProfileService.class);
        SparseMigrationService sparseMigrationService = mock(SparseMigrationService.class);
        KnowledgeBaseController controller = new KnowledgeBaseController(kbService, retrievalService, chatService,
                ingestJobService, chatSessionService, ragEvalService, knowledgeBaseExportService,
                retrievalProfileService, sparseMigrationService);
        UUID kbId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID secondSessionId = UUID.randomUUID();
        CreateKnowledgeBaseRequest create = new CreateKnowledgeBaseRequest();
        RetrieveRequest retrieve = new RetrieveRequest();
        retrieve.setQuery("q");
        ChatRequest streamChat = new ChatRequest();
        streamChat.setQuery("q");
        ChatRequest syncChat = new ChatRequest();
        syncChat.setQuery("q");
        syncChat.setStream(false);
        CreateChatSessionRequest createSession = new CreateChatSessionRequest();
        createSession.setTitle("Session");
        UpdateChatSessionRequest renameSession = new UpdateChatSessionRequest();
        renameSession.setTitle("Renamed");
        BatchDeleteChatSessionsRequest batchDeleteSessions = new BatchDeleteChatSessionsRequest();
        batchDeleteSessions.setSessionIds(List.of(sessionId, secondSessionId));
        KnowledgeBaseResponse kbResponse = KnowledgeBaseResponse.builder().id(kbId).name("KB").build();
        RetrieveResponse retrieveResponse = RetrieveResponse.builder().query("q").hits(List.of()).build();
        IngestJobResponse jobResponse = IngestJobResponse.builder().kbId(kbId).build();
        IngestJobResponse retryResponse = IngestJobResponse.builder().id(UUID.randomUUID()).kbId(kbId).build();
        IngestJobResponse cancelResponse = IngestJobResponse.builder()
                .id(UUID.randomUUID())
                .kbId(kbId)
                .status(com.dupi.rag.domain.enums.IngestJobStatus.CANCEL_REQUESTED)
                .build();
        RagEvalRunResponse ragEvalRun = RagEvalRunResponse.builder()
                .id(UUID.randomUUID())
                .kbId(kbId)
                .passedCount(0)
                .totalCount(0)
                .results(List.of())
                .build();
        RagEvalCaseResponse ragEvalCase = RagEvalCaseResponse.builder()
                .id(UUID.randomUUID())
                .kbId(kbId)
                .caseKey("case")
                .query("q")
                .build();
        RagQualityPolicyRequest qualityPolicyRequest = new RagQualityPolicyRequest();
        qualityPolicyRequest.setMinimumPassRate(80);
        qualityPolicyRequest.setMaximumPassRateDrop(5);
        qualityPolicyRequest.setMaximumNewFailures(0);
        qualityPolicyRequest.setBlockWhenUnbaselined(false);
        RagQualityPolicyResponse qualityPolicy = RagQualityPolicyResponse.builder()
                .kbId(kbId)
                .minimumPassRate(80)
                .maximumPassRateDrop(5)
                .maximumNewFailures(0)
                .blockWhenUnbaselined(false)
                .build();
        RetrievalProfileRequest profileRequest = new RetrievalProfileRequest();
        profileRequest.setName("balanced");
        profileRequest.setVectorCandidateCount(30);
        profileRequest.setSparseCandidateCount(30);
        profileRequest.setRrfConstant(60);
        profileRequest.setRerankEnabled(true);
        profileRequest.setRerankCandidateLimit(20);
        profileRequest.setFinalTopK(5);
        RetrievalProfileResponse profileResponse = RetrievalProfileResponse.builder()
                .id(UUID.randomUUID()).kbId(kbId).name("balanced").version(1).build();
        SparseMigrationResponse migrationResponse = SparseMigrationResponse.builder().id(UUID.randomUUID())
                .kbId(kbId).profileId(profileResponse.getId())
                .state(com.dupi.rag.domain.enums.SparseMigrationState.PREPARING).build();
        KnowledgeBaseExportResponse exportResponse = KnowledgeBaseExportResponse.builder()
                .knowledgeBase(KnowledgeBaseExportResponse.KnowledgeBaseSnapshot.builder()
                        .originalId(kbId)
                        .name("KB")
                        .build())
                .documents(List.of())
                .chunks(List.of())
                .evalCases(List.of())
                .build();
        KnowledgeBaseImportRequest importRequest = new KnowledgeBaseImportRequest();
        KnowledgeBaseImportRequest.KnowledgeBaseSnapshot importSnapshot =
                new KnowledgeBaseImportRequest.KnowledgeBaseSnapshot();
        importSnapshot.setName("KB");
        importRequest.setKnowledgeBase(importSnapshot);
        ChatSessionResponse sessionResponse = ChatSessionResponse.builder().id(sessionId).kbId(kbId).title("Session").build();
        ChatSessionDetailResponse sessionDetail = ChatSessionDetailResponse.builder()
                .session(sessionResponse)
                .messages(List.of())
                .build();
        when(kbService.create(create)).thenReturn(kbResponse);
        when(kbService.list()).thenReturn(List.of(kbResponse));
        when(kbService.get(kbId)).thenReturn(kbResponse);
        when(retrievalService.retrieve(kbId, retrieve)).thenReturn(retrieveResponse);
        when(chatService.chatStream(kbId, streamChat)).thenReturn(Flux.just(ServerSentEvent.<String>builder().event("done").data("{}").build()));
        when(chatService.chat(kbId, syncChat)).thenReturn("answer");
        when(ingestJobService.listByKb(kbId)).thenReturn(List.of(jobResponse));
        when(ingestJobService.retryForKnowledgeBase(kbId, retryResponse.getId())).thenReturn(retryResponse);
        when(ingestJobService.cancelForKnowledgeBase(kbId, cancelResponse.getId())).thenReturn(cancelResponse);
        when(chatSessionService.list(kbId)).thenReturn(List.of(sessionResponse));
        when(chatSessionService.create(kbId, createSession)).thenReturn(sessionResponse);
        when(chatSessionService.getDetail(kbId, sessionId)).thenReturn(sessionDetail);
        when(chatSessionService.rename(kbId, sessionId, renameSession)).thenReturn(sessionResponse);
        when(ingestJobService.reindexKnowledgeBase(kbId)).thenReturn(List.of(jobResponse));
        when(ragEvalService.listCases(kbId)).thenReturn(List.of(ragEvalCase));
        when(ragEvalService.createCase(eq(kbId), any(RagEvalCaseRequest.class))).thenReturn(ragEvalCase);
        when(ragEvalService.updateCase(eq(kbId), eq(ragEvalCase.getId()), any(RagEvalCaseRequest.class))).thenReturn(ragEvalCase);
        when(ragEvalService.listRuns(kbId)).thenReturn(List.of(ragEvalRun));
        when(ragEvalService.run(eq(kbId), eq(true), isNull(), isNull())).thenReturn(ragEvalRun);
        when(ragEvalService.getPolicy(kbId)).thenReturn(qualityPolicy);
        when(ragEvalService.updatePolicy(kbId, qualityPolicyRequest)).thenReturn(qualityPolicy);
        when(ragEvalService.promoteBaseline(kbId, ragEvalRun.getId())).thenReturn(qualityPolicy);
        when(ragEvalService.getRunComparison(kbId, ragEvalRun.getId())).thenReturn(ragEvalRun);
        when(retrievalProfileService.list(kbId)).thenReturn(List.of(profileResponse));
        when(retrievalProfileService.create(kbId, profileRequest)).thenReturn(profileResponse);
        when(retrievalProfileService.activate(kbId, profileResponse.getId())).thenReturn(profileResponse);
        when(retrievalProfileService.rollback(kbId, profileResponse.getId())).thenReturn(profileResponse);
        when(sparseMigrationService.list(kbId)).thenReturn(List.of(migrationResponse));
        when(sparseMigrationService.start(kbId, profileResponse.getId())).thenReturn(migrationResponse);
        when(sparseMigrationService.backfill(kbId, migrationResponse.getId())).thenReturn(migrationResponse);
        when(sparseMigrationService.beginShadowValidation(kbId, migrationResponse.getId())).thenReturn(migrationResponse);
        when(sparseMigrationService.recordShadowValidation(eq(kbId), eq(migrationResponse.getId()), any()))
                .thenReturn(migrationResponse);
        when(sparseMigrationService.cutover(kbId, migrationResponse.getId())).thenReturn(migrationResponse);
        when(sparseMigrationService.complete(kbId, migrationResponse.getId())).thenReturn(migrationResponse);
        when(sparseMigrationService.setLegacyFallback(kbId, migrationResponse.getId(), true)).thenReturn(migrationResponse);
        when(knowledgeBaseExportService.exportKnowledgeBase(kbId)).thenReturn(exportResponse);
        when(knowledgeBaseExportService.restore(importRequest)).thenReturn(kbResponse);

        assertThat(controller.create(create)).isSameAs(kbResponse);
        assertThat(controller.list()).containsExactly(kbResponse);
        assertThat(controller.get(kbId)).isSameAs(kbResponse);
        controller.delete(kbId);
        assertThat(controller.retrieve(kbId, retrieve)).isSameAs(retrieveResponse);
        assertThat(controller.chatStream(kbId, streamChat).collectList().block()).hasSize(1);
        assertThat(controller.chatStream(kbId, syncChat).collectList().block()).extracting(ServerSentEvent::event).containsExactly("token", "done");
        assertThat(controller.cancelChat(Map.of("sessionId", "s1"))).containsEntry("status", "cancel_requested");
        assertThat(controller.cancelChat(Map.of())).containsEntry("status", "cancel_requested");
        assertThat(controller.listJobs(kbId)).containsExactly(jobResponse);
        assertThat(controller.retryJob(kbId, retryResponse.getId())).isSameAs(retryResponse);
        assertThat(controller.cancelJob(kbId, cancelResponse.getId())).isSameAs(cancelResponse);
        assertThat(controller.reindex(kbId)).containsExactly(jobResponse);
        assertThat(controller.exportKnowledgeBase(kbId)).isSameAs(exportResponse);
        assertThat(controller.importKnowledgeBase(importRequest)).isSameAs(kbResponse);
        assertThat(controller.listChatSessions(kbId)).containsExactly(sessionResponse);
        assertThat(controller.createChatSession(kbId, createSession)).isSameAs(sessionResponse);
        assertThat(controller.getChatSession(kbId, sessionId)).isSameAs(sessionDetail);
        assertThat(controller.renameChatSession(kbId, sessionId, renameSession)).isSameAs(sessionResponse);
        controller.deleteChatSession(kbId, sessionId);
        controller.batchDeleteChatSessions(kbId, batchDeleteSessions);
        RagEvalCaseRequest ragEvalRequest = new RagEvalCaseRequest();
        ragEvalRequest.setCaseKey("case");
        ragEvalRequest.setQuery("q");
        assertThat(controller.listRagEvalCases(kbId)).containsExactly(ragEvalCase);
        assertThat(controller.createRagEvalCase(kbId, ragEvalRequest)).isSameAs(ragEvalCase);
        assertThat(controller.updateRagEvalCase(kbId, ragEvalCase.getId(), ragEvalRequest)).isSameAs(ragEvalCase);
        controller.deleteRagEvalCase(kbId, ragEvalCase.getId());
        assertThat(controller.listRagEvalRuns(kbId)).containsExactly(ragEvalRun);
        RagEvalRunRequest ragEvalRunRequest = new RagEvalRunRequest();
        ragEvalRunRequest.setUseRerank(true);
        assertThat(controller.runRagEval(kbId, ragEvalRunRequest)).isSameAs(ragEvalRun);
        assertThat(controller.getRagQualityPolicy(kbId)).isSameAs(qualityPolicy);
        assertThat(controller.updateRagQualityPolicy(kbId, qualityPolicyRequest)).isSameAs(qualityPolicy);
        assertThat(controller.promoteRagEvalBaseline(kbId, ragEvalRun.getId())).isSameAs(qualityPolicy);
        assertThat(controller.getRagEvalRunComparison(kbId, ragEvalRun.getId())).isSameAs(ragEvalRun);
        assertThat(controller.listRetrievalProfiles(kbId)).containsExactly(profileResponse);
        assertThat(controller.createRetrievalProfile(kbId, profileRequest)).isSameAs(profileResponse);
        assertThat(controller.activateRetrievalProfile(kbId, profileResponse.getId())).isSameAs(profileResponse);
        assertThat(controller.rollbackRetrievalProfile(kbId, profileResponse.getId())).isSameAs(profileResponse);
        assertThat(controller.listSparseMigrations(kbId)).containsExactly(migrationResponse);
        assertThat(controller.startSparseMigration(kbId, profileResponse.getId())).isSameAs(migrationResponse);
        assertThat(controller.backfillSparseMigration(kbId, migrationResponse.getId())).isSameAs(migrationResponse);
        assertThat(controller.beginSparseShadowValidation(kbId, migrationResponse.getId())).isSameAs(migrationResponse);
        assertThat(controller.validateSparseMigration(kbId, migrationResponse.getId(),
                new SparseMigrationValidationRequest())).isSameAs(migrationResponse);
        assertThat(controller.cutoverSparseMigration(kbId, migrationResponse.getId())).isSameAs(migrationResponse);
        assertThat(controller.completeSparseMigration(kbId, migrationResponse.getId())).isSameAs(migrationResponse);
        assertThat(controller.setLegacySparseFallback(kbId, migrationResponse.getId(), true)).isSameAs(migrationResponse);

        verify(kbService).delete(kbId);
        verify(chatService).cancel("s1");
        verify(chatService, never()).cancel(null);
        verify(chatSessionService).delete(kbId, sessionId);
        verify(chatSessionService).batchDelete(kbId, List.of(sessionId, secondSessionId));
        verify(ragEvalService).deleteCase(kbId, ragEvalCase.getId());
    }

    @Test
    void opsControllerDelegatesVectorCleanupTaskListAndRetry() {
        VectorCleanupTaskService service = mock(VectorCleanupTaskService.class);
        AuditLogService auditLogService = mock(AuditLogService.class);
        AccountService accountService = mock(AccountService.class);
        RoleService roleService = mock(RoleService.class);
        IngestJobService ingestJobService = mock(IngestJobService.class);
        UploadRateLimitProperties uploadRateLimitProperties = new UploadRateLimitProperties();
        uploadRateLimitProperties.setEnabled(true);
        uploadRateLimitProperties.setRequests(11);
        uploadRateLimitProperties.setWindowSeconds(45);
        RedisQueueProperties redisQueueProperties = new RedisQueueProperties();
        redisQueueProperties.setMaxPendingJobs(123);
        redisQueueProperties.setMaxRecoveryAttempts(5);
        AuditProperties auditProperties = new AuditProperties();
        auditProperties.setAlertWindowMinutes(12);
        auditProperties.setAlertFailedThreshold(7);
        MultipartProperties multipartProperties = new MultipartProperties();
        multipartProperties.setMaxFileSize(DataSize.ofMegabytes(32));
        OpsNotificationService opsNotificationService = mock(OpsNotificationService.class);
        OpsController controller = new OpsController(
                service,
                auditLogService,
                accountService,
                roleService,
                ingestJobService,
                uploadRateLimitProperties,
                redisQueueProperties,
                auditProperties,
                multipartProperties,
                opsNotificationService
        );
        UUID taskId = UUID.randomUUID();
        VectorCleanupTaskResponse response = VectorCleanupTaskResponse.builder()
                .id(taskId)
                .targetType(com.dupi.rag.domain.enums.VectorCleanupTargetType.DOCUMENT)
                .targetId(UUID.randomUUID())
                .status(com.dupi.rag.domain.enums.VectorCleanupStatus.PENDING)
                .attemptCount(1)
                .build();
        AuditLogResponse auditLog = AuditLogResponse.builder()
                .id(UUID.randomUUID())
                .tenantId("tenant-a")
                .action("DOCUMENT_DELETE")
                .targetType("DOCUMENT")
                .targetId(UUID.randomUUID())
                .status(com.dupi.rag.domain.enums.AuditLogStatus.SUCCESS)
                .createdAt(Instant.parse("2026-07-06T08:00:00Z"))
                .build();
        when(service.listOpenTasks()).thenReturn(List.of(response));
        when(service.retry(taskId)).thenReturn(response);
        when(auditLogService.list(any(AuditLogQuery.class))).thenReturn(List.of(auditLog));
        when(auditLogService.exportCsv(any(AuditLogQuery.class))).thenReturn("createdAt,tenantId\n");
        when(auditLogService.summarizeAlerts()).thenReturn(List.of(AuditAlertResponse.builder()
                .code("AUDIT_FAILED_SPIKE")
                .severity("WARN")
                .message("Too many failed audit events")
                .count(3)
                .threshold(2)
                .build()));
        when(ingestJobService.summarizeAlerts()).thenReturn(List.of(AuditAlertResponse.builder()
                .code("INGEST_FAILURES_OPEN")
                .severity("WARN")
                .message("Open ingest failures")
                .count(2)
                .threshold(0)
                .build()));
        when(service.summarizeAlerts()).thenReturn(List.of(AuditAlertResponse.builder()
                .code("VECTOR_CLEANUP_FAILURES_OPEN")
                .severity("WARN")
                .message("Open vector cleanup failures")
                .count(1)
                .threshold(0)
                .build()));
        when(accountService.listUsers()).thenReturn(List.of(AccountResponse.builder()
                .username("admin")
                .tenantId("ops")
                .role("ADMIN")
                .permissions(List.of("*"))
                .knowledgeBaseIds(List.of())
                .tokenVersion("1")
                .passwordConfigured(true)
                .passwordHashConfigured(false)
                .build()));
        AccountUpsertRequest accountRequest = new AccountUpsertRequest();
        accountRequest.setUsername("analyst");
        accountRequest.setPassword("secret");
        AccountResponse analyst = AccountResponse.builder()
                .username("analyst")
                .tenantId("tenant-a")
                .role("USER")
                .permissions(List.of("KB_READ"))
                .knowledgeBaseIds(List.of("kb-1"))
                .tokenVersion("1")
                .passwordConfigured(false)
                .passwordHashConfigured(true)
                .build();
        when(accountService.create(accountRequest)).thenReturn(analyst);
        when(accountService.update("analyst", accountRequest)).thenReturn(analyst);
        when(accountService.disable("analyst")).thenReturn(analyst);
        when(accountService.enable("analyst")).thenReturn(analyst);
        when(accountService.rotateTokenVersion("analyst")).thenReturn(analyst);
        when(accountService.deleteE2e("e2e_account_42")).thenReturn("e2e_account_42");
        when(accountService.generatePasswordHash("secret")).thenReturn("pbkdf2$hash");
        when(accountService.resetPassword(eq("analyst"), eq("secret"))).thenReturn(analyst);
        RoleResponse role = RoleResponse.builder()
                .id(UUID.randomUUID())
                .code("ANALYST")
                .name("分析用户")
                .permissions(List.of("KB_READ"))
                .build();
        when(roleService.listRoles()).thenReturn(List.of(role));
        when(roleService.create(any(RoleRequest.class))).thenReturn(role);
        when(roleService.update(eq(role.getId()), any(RoleRequest.class))).thenReturn(role);
        when(roleService.disable(role.getId())).thenReturn(role);
        OpsNotificationResponse notification = OpsNotificationResponse.builder()
                .configured(true)
                .delivered(true)
                .alertCount(3)
                .statusCode(202)
                .build();
        when(opsNotificationService.notifyAlerts(any())).thenReturn(notification);

        assertThat(controller.listVectorCleanupTasks()).containsExactly(response);
        assertThat(controller.retryVectorCleanupTask(taskId)).isSameAs(response);
        assertThat(controller.listAuditLogs("tenant-a", "DOCUMENT_DELETE", "DOCUMENT", "SUCCESS", 25))
                .containsExactly(auditLog);
        assertThat(controller.exportAuditLogs("tenant-a", "DOCUMENT_DELETE", "DOCUMENT", "SUCCESS"))
                .contains("createdAt,tenantId");
        assertThat(controller.listAuditAlerts()).extracting(AuditAlertResponse::getCode)
                .containsExactly("AUDIT_FAILED_SPIKE", "INGEST_FAILURES_OPEN", "VECTOR_CLEANUP_FAILURES_OPEN");
        assertThat(controller.notifyAuditAlerts()).isSameAs(notification);
        assertThat(controller.listAccounts()).extracting(AccountResponse::getUsername)
                .containsExactly("admin");
        assertThat(controller.createAccount(accountRequest)).isSameAs(analyst);
        assertThat(controller.updateAccount("analyst", accountRequest)).isSameAs(analyst);
        assertThat(controller.disableAccount("analyst")).isSameAs(analyst);
        assertThat(controller.enableAccount("analyst")).isSameAs(analyst);
        assertThat(controller.rotateAccountToken("analyst")).isSameAs(analyst);
        controller.deleteE2eAccount("e2e_account_42");
        PasswordResetRequest passwordResetRequest = new PasswordResetRequest();
        passwordResetRequest.setPassword("secret");
        assertThat(controller.resetAccountPassword("analyst", passwordResetRequest)).isSameAs(analyst);
        assertThat(controller.generatePasswordHash(Map.of("password", "secret"))).containsEntry("passwordHash", "pbkdf2$hash");
        OpsMetadataResponse metadata = controller.metadata();
        assertThat(metadata.getPermissions()).contains("KB_READ", "ACCOUNT_PASSWORD_RESET", "OPS_ALERT_NOTIFY");
        assertThat(metadata.getAuditActions()).contains("ACCOUNT_DELETE_E2E");
        assertThat(metadata.getGuardrails().getUploadRateLimit().isEnabled()).isTrue();
        assertThat(metadata.getGuardrails().getUploadRateLimit().getRequests()).isEqualTo(11);
        assertThat(metadata.getGuardrails().getUploadRateLimit().getWindowSeconds()).isEqualTo(45);
        assertThat(metadata.getGuardrails().getIngestQueue().getMaxPendingJobs()).isEqualTo(123);
        assertThat(metadata.getGuardrails().getIngestQueue().getMaxRecoveryAttempts()).isEqualTo(5);
        assertThat(metadata.getGuardrails().getAudit().getAlertWindowMinutes()).isEqualTo(12);
        assertThat(metadata.getGuardrails().getAudit().getAlertFailedThreshold()).isEqualTo(7);
        assertThat(metadata.getGuardrails().getMultipart().getMaxFileSizeBytes()).isEqualTo(DataSize.ofMegabytes(32).toBytes());
        assertThat(metadata.getPermissionDetails())
                .extracting(PermissionMetadataResponse::getCode)
                .contains("KB_READ", "ACCOUNT_PASSWORD_RESET");
        assertThat(metadata.getPermissionDetails())
                .filteredOn(detail -> "ACCOUNT_PASSWORD_RESET".equals(detail.getCode()))
                .singleElement()
                .satisfies(detail -> {
                    assertThat(detail.getName()).isNotBlank();
                    assertThat(detail.getAllows()).contains("重置其他账号密码");
                    assertThat(detail.getDenies()).contains("创建、禁用或编辑账号基础信息");
                });
        assertThat(metadata.getPermissionDetails())
                .filteredOn(detail -> "KB_READ".equals(detail.getCode()))
                .singleElement()
                .satisfies(detail -> assertThat(detail.getAllows()).contains("查看 RAG 评估用例和历史", "导出知识库快照"));
        assertThat(metadata.getPermissionDetails())
                .filteredOn(detail -> "KB_WRITE".equals(detail.getCode()))
                .singleElement()
                .satisfies(detail -> assertThat(detail.getAllows()).contains("导入知识库快照", "管理 RAG 评估用例"));
        assertThat(metadata.getPermissionDetails())
                .filteredOn(detail -> "MAINTENANCE".equals(detail.getCode()))
                .singleElement()
                .satisfies(detail -> assertThat(detail.getAllows()).contains("运行 RAG 评估"));
        assertThat(controller.listRoles()).containsExactly(role);
        RoleRequest roleRequest = new RoleRequest();
        roleRequest.setCode("ANALYST");
        assertThat(controller.createRole(roleRequest)).isSameAs(role);
        assertThat(controller.updateRole(role.getId(), roleRequest)).isSameAs(role);
        assertThat(controller.disableRole(role.getId())).isSameAs(role);
        verify(auditLogService).list(any(AuditLogQuery.class));
        verify(auditLogService).exportCsv(any(AuditLogQuery.class));
        verify(auditLogService, times(2)).summarizeAlerts();
        verify(ingestJobService, times(2)).summarizeAlerts();
        verify(service, times(2)).summarizeAlerts();
        verify(opsNotificationService).notifyAlerts(any());
        verify(auditLogService).recordSuccess(eq("AUDIT_ALERT_NOTIFY"), eq("AUDIT_ALERT"), isNull(), anyString());
        verify(accountService).listUsers();
        verify(accountService).create(accountRequest);
        verify(accountService).update("analyst", accountRequest);
        verify(accountService).disable("analyst");
        verify(accountService).enable("analyst");
        verify(accountService).rotateTokenVersion("analyst");
        verify(accountService).deleteE2e("e2e_account_42");
        verify(accountService).resetPassword("analyst", "secret");
        verify(auditLogService).recordSuccess(eq("ACCOUNT_CREATE"), eq("ACCOUNT"), isNull(), anyString());
        verify(auditLogService).recordSuccess(eq("ACCOUNT_UPDATE"), eq("ACCOUNT"), isNull(), anyString());
        verify(auditLogService).recordSuccess(eq("ACCOUNT_PASSWORD_RESET"), eq("ACCOUNT"), isNull(), anyString());
        verify(auditLogService).recordSuccess(eq("ACCOUNT_DELETE_E2E"), eq("ACCOUNT"), isNull(), contains("e2e_account_42"));
        verify(auditLogService).recordSuccess(eq("ROLE_CREATE"), eq("ROLE"), eq(role.getId()), anyString());
    }

    @Test
    void internalControllerMapsChunksWithFileNamesAndEmptyMetadata() {
        KnowledgeBaseService kbService = mock(KnowledgeBaseService.class);
        ChunkRepository chunkRepository = mock(ChunkRepository.class);
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        InternalController controller = new InternalController(kbService, chunkRepository, documentRepository);
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        when(documentRepository.findByKbIdOrderByCreatedAtDesc(kbId)).thenReturn(List.of(Document.builder()
                .id(docId)
                .kbId(kbId)
                .fileName("doc.md")
                .objectKey("obj")
                .mimeType("text/markdown")
                .fileSize(1L)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build()));
        when(chunkRepository.findByKbIdOrderByChunkIndexAsc(kbId)).thenReturn(List.of(
                Chunk.builder().id(chunkId).kbId(kbId).docId(docId).chunkIndex(0).content("content").metadata(null).build()
        ));

        List<Map<String, Object>> chunks = controller.listChunks(kbId);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).containsEntry("chunk_id", chunkId.toString())
                .containsEntry("doc_id", docId.toString())
                .containsEntry("file_name", "doc.md")
                .containsEntry("content", "content")
                .containsEntry("metadata", Map.of());
        verify(kbService).findSystemOrThrow(kbId);
    }

    private static ApiSecurityProperties.UserAccount user(String username, String password, String tenantId, String role) {
        ApiSecurityProperties.UserAccount user = new ApiSecurityProperties.UserAccount();
        user.setUsername(username);
        user.setPassword(password);
        user.setTenantId(tenantId);
        user.setRole(role);
        return user;
    }
}
