package com.dupi.rag.domain;

import com.dupi.rag.domain.entity.Chunk;
import com.dupi.rag.domain.entity.AuditLog;
import com.dupi.rag.domain.entity.ChatMessage;
import com.dupi.rag.domain.entity.ChatSession;
import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.DocumentTombstone;
import com.dupi.rag.domain.entity.IngestJob;
import com.dupi.rag.domain.entity.IngestOutboxEvent;
import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.domain.entity.RagEvalCase;
import com.dupi.rag.domain.entity.RagEvalRun;
import com.dupi.rag.domain.entity.RagEvalRunResult;
import com.dupi.rag.domain.entity.Role;
import com.dupi.rag.domain.entity.UserAccount;
import com.dupi.rag.domain.entity.VectorCleanupTask;
import com.dupi.rag.domain.enums.AuditLogStatus;
import com.dupi.rag.domain.enums.ChatMessageRole;
import com.dupi.rag.domain.enums.DocumentStatus;
import com.dupi.rag.domain.enums.RagEvalRunStatus;
import com.dupi.rag.domain.enums.VectorCleanupStatus;
import com.dupi.rag.domain.enums.VectorCleanupTargetType;
import com.dupi.rag.domain.enums.IngestOutboxStatus;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EntityLifecycleTest {

    @Test
    void entityLifecycleCallbacksPopulateIdsAndTimestamps() throws Exception {
        KnowledgeBase kb = KnowledgeBase.builder().name("KB").build();
        invoke(kb, "onCreate");
        assertThat(kb.getId()).isNotNull();
        assertThat(kb.getCreatedAt()).isNotNull();
        assertThat(kb.getUpdatedAt()).isNotNull();
        invoke(kb, "onUpdate");
        assertThat(kb.getUpdatedAt()).isNotNull();

        Document doc = Document.builder()
                .kbId(kb.getId())
                .fileName("a.md")
                .objectKey("obj")
                .mimeType("text/markdown")
                .build();
        invoke(doc, "onCreate");
        assertThat(doc.getId()).isNotNull();
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.PENDING);
        invoke(doc, "onUpdate");
        assertThat(doc.getUpdatedAt()).isNotNull();

        IngestJob job = IngestJob.builder().kbId(kb.getId()).docId(doc.getId()).build();
        invoke(job, "onCreate");
        assertThat(job.getId()).isNotNull();
        assertThat(job.getCreatedAt()).isNotNull();
        invoke(job, "onUpdate");
        assertThat(job.getUpdatedAt()).isNotNull();

        Chunk chunk = Chunk.builder().kbId(kb.getId()).docId(doc.getId()).chunkIndex(0).content("c").build();
        invoke(chunk, "onCreate");
        assertThat(chunk.getId()).isNotNull();
        assertThat(chunk.getCreatedAt()).isNotNull();
        assertThat(chunk.getTokenCount()).isZero();

        Role role = Role.builder()
                .code("SUPPORT")
                .name("Support")
                .permissions("KB_READ")
                .build();
        invoke(role, "onCreate");
        assertThat(role.getId()).isNotNull();
        assertThat(role.getCreatedAt()).isNotNull();
        assertThat(role.getUpdatedAt()).isNotNull();
        Instant roleCreatedAt = role.getCreatedAt();
        invoke(role, "onUpdate");
        assertThat(role.getCreatedAt()).isEqualTo(roleCreatedAt);
        assertThat(role.getUpdatedAt()).isNotNull();

        UserAccount userAccount = UserAccount.builder()
                .username("alice")
                .passwordHash("hash")
                .build();
        invoke(userAccount, "onCreate");
        assertThat(userAccount.getId()).isNotNull();
        assertThat(userAccount.getTenantId()).isEqualTo("default");
        assertThat(userAccount.getRoleCode()).isEqualTo("ANALYST");
        assertThat(userAccount.getTokenVersion()).isEqualTo("1");
        assertThat(userAccount.isDisabled()).isFalse();
        assertThat(userAccount.getCreatedAt()).isNotNull();
        assertThat(userAccount.getUpdatedAt()).isNotNull();
        Instant userUpdatedAt = userAccount.getUpdatedAt();
        invoke(userAccount, "onUpdate");
        assertThat(userAccount.getUpdatedAt()).isAfterOrEqualTo(userUpdatedAt);
    }

    @Test
    void auditAndVectorCleanupLifecyclePopulateOperationalDefaults() throws Exception {
        AuditLog auditLog = AuditLog.builder()
                .action("DOCUMENT_DELETE")
                .targetType("DOCUMENT")
                .targetId(UUID.randomUUID())
                .status(AuditLogStatus.SUCCESS)
                .message("deleted")
                .build();
        invoke(auditLog, "onCreate");

        assertThat(auditLog.getId()).isNotNull();
        assertThat(auditLog.getTenantId()).isEqualTo("default");
        assertThat(auditLog.getAction()).isEqualTo("DOCUMENT_DELETE");
        assertThat(auditLog.getTargetType()).isEqualTo("DOCUMENT");
        assertThat(auditLog.getTargetId()).isNotNull();
        assertThat(auditLog.getStatus()).isEqualTo(AuditLogStatus.SUCCESS);
        assertThat(auditLog.getMessage()).isEqualTo("deleted");
        assertThat(auditLog.getErrorMessage()).isNull();
        assertThat(auditLog.getCreatedAt()).isNotNull();

        VectorCleanupTask task = VectorCleanupTask.builder()
                .targetType(VectorCleanupTargetType.KNOWLEDGE_BASE)
                .targetId(UUID.randomUUID())
                .build();
        invoke(task, "onCreate");
        assertThat(task.getId()).isNotNull();
        assertThat(task.getStatus()).isEqualTo(VectorCleanupStatus.PENDING);
        assertThat(task.getAttemptCount()).isZero();
        assertThat(task.getNextAttemptAt()).isNotNull();
        assertThat(task.getCreatedAt()).isNotNull();
        assertThat(task.getUpdatedAt()).isNotNull();

        Instant createdAt = task.getCreatedAt();
        invoke(task, "onUpdate");
        assertThat(task.getCreatedAt()).isEqualTo(createdAt);
        assertThat(task.getUpdatedAt()).isNotNull();
    }

    @Test
    void tombstoneAndOutboxLifecyclePopulateOperationalDefaults() throws Exception {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();

        DocumentTombstone tombstone = DocumentTombstone.builder()
                .docId(docId)
                .kbId(kbId)
                .objectKey("kb/doc/a.md")
                .fileName("a.md")
                .build();
        invoke(tombstone, "onCreate");

        assertThat(tombstone.getDocId()).isEqualTo(docId);
        assertThat(tombstone.getKbId()).isEqualTo(kbId);
        assertThat(tombstone.getObjectKey()).isEqualTo("kb/doc/a.md");
        assertThat(tombstone.getFileName()).isEqualTo("a.md");
        assertThat(tombstone.getReason()).isEqualTo("DOCUMENT_DELETE");
        assertThat(tombstone.getCreatedAt()).isNotNull();

        Instant originalCreatedAt = Instant.parse("2026-07-07T00:00:00Z");
        DocumentTombstone existingTombstone = DocumentTombstone.builder()
                .docId(docId)
                .kbId(kbId)
                .createdAt(originalCreatedAt)
                .reason("MANUAL_DELETE")
                .build();
        invoke(existingTombstone, "onCreate");
        assertThat(existingTombstone.getCreatedAt()).isEqualTo(originalCreatedAt);
        assertThat(existingTombstone.getReason()).isEqualTo("MANUAL_DELETE");

        IngestOutboxEvent event = IngestOutboxEvent.builder()
                .jobId(jobId)
                .kbId(kbId)
                .docId(docId)
                .objectKey("kb/doc/a.md")
                .fileName("a.md")
                .mimeType("text/markdown")
                .build();
        invoke(event, "onCreate");

        assertThat(event.getId()).isNotNull();
        assertThat(event.getJobId()).isEqualTo(jobId);
        assertThat(event.getKbId()).isEqualTo(kbId);
        assertThat(event.getDocId()).isEqualTo(docId);
        assertThat(event.getObjectKey()).isEqualTo("kb/doc/a.md");
        assertThat(event.getFileName()).isEqualTo("a.md");
        assertThat(event.getMimeType()).isEqualTo("text/markdown");
        assertThat(event.getStatus()).isEqualTo(IngestOutboxStatus.PENDING);
        assertThat(event.getAttemptCount()).isZero();
        assertThat(event.getCreatedAt()).isNotNull();
        assertThat(event.getUpdatedAt()).isNotNull();
        assertThat(event.getNextAttemptAt()).isNotNull();
        assertThat(event.getLastError()).isNull();

        Instant previousUpdatedAt = event.getUpdatedAt();
        Thread.sleep(2L);
        invoke(event, "onUpdate");
        assertThat(event.getUpdatedAt()).isAfterOrEqualTo(previousUpdatedAt);

        IngestOutboxEvent nullAttemptEvent = IngestOutboxEvent.builder()
                .jobId(jobId)
                .kbId(kbId)
                .docId(docId)
                .objectKey("kb/doc/b.md")
                .fileName("b.md")
                .mimeType("text/markdown")
                .attemptCount(null)
                .build();
        invoke(nullAttemptEvent, "onCreate");
        assertThat(nullAttemptEvent.getAttemptCount()).isZero();
    }

    @Test
    void chatSessionAndMessageLifecycleDefaultsIdsAndTimestamps() throws Exception {
        ChatSession session = ChatSession.builder()
                .kbId(UUID.randomUUID())
                .tenantId("default")
                .title("First question")
                .build();
        invoke(session, "onCreate");

        ChatMessage message = ChatMessage.builder()
                .sessionId(session.getId())
                .sequenceNumber(0)
                .role(ChatMessageRole.USER)
                .content("What is dupi-RAG?")
                .citations(Map.of("source", "a.md"))
                .build();
        invoke(message, "onCreate");

        assertThat(session.getId()).isNotNull();
        assertThat(session.getCreatedAt()).isNotNull();
        assertThat(session.getUpdatedAt()).isNotNull();
        Instant sessionUpdatedAt = session.getUpdatedAt();
        invoke(session, "onUpdate");
        assertThat(session.getUpdatedAt()).isAfterOrEqualTo(sessionUpdatedAt);
        assertThat(message.getId()).isNotNull();
        assertThat(message.getCreatedAt()).isNotNull();
        assertThat(message.getSequenceNumber()).isZero();
        assertThat(message.getRole()).isEqualTo(ChatMessageRole.USER);
        assertThat(message.getCitations()).containsEntry("source", "a.md");
    }

    @Test
    void ragEvaluationEntitiesExposeFieldsAndPopulateLifecycleValues() throws Exception {
        UUID kbId = UUID.randomUUID();
        RagEvalCase evalCase = RagEvalCase.builder()
                .kbId(kbId)
                .caseKey("case-1")
                .query("query")
                .minHits(2)
                .topK(8)
                .expectedFileName("guide.md")
                .mustContainAny(List.of("token"))
                .build();
        invoke(evalCase, "onCreate");
        Instant caseCreatedAt = evalCase.getCreatedAt();
        invoke(evalCase, "onUpdate");
        assertThat(evalCase.getId()).isNotNull();
        assertThat(evalCase.getKbId()).isEqualTo(kbId);
        assertThat(evalCase.getCaseKey()).isEqualTo("case-1");
        assertThat(evalCase.getQuery()).isEqualTo("query");
        assertThat(evalCase.getMinHits()).isEqualTo(2);
        assertThat(evalCase.getTopK()).isEqualTo(8);
        assertThat(evalCase.getExpectedFileName()).isEqualTo("guide.md");
        assertThat(evalCase.getMustContainAny()).containsExactly("token");
        assertThat(evalCase.getCreatedAt()).isEqualTo(caseCreatedAt);
        assertThat(evalCase.getUpdatedAt()).isAfterOrEqualTo(caseCreatedAt);

        RagEvalRun run = RagEvalRun.builder()
                .kbId(kbId)
                .useRerank(true)
                .passedCount(1)
                .totalCount(2)
                .build();
        invoke(run, "onCreate");
        assertThat(run.getId()).isNotNull();
        assertThat(run.getKbId()).isEqualTo(kbId);
        assertThat(run.getUseRerank()).isTrue();
        assertThat(run.getPassedCount()).isEqualTo(1);
        assertThat(run.getTotalCount()).isEqualTo(2);
        assertThat(run.getStatus()).isEqualTo(RagEvalRunStatus.RUNNING);
        assertThat(run.getFailureMessage()).isNull();
        assertThat(run.getCreatedAt()).isNotNull();

        RagEvalRunResult result = RagEvalRunResult.builder()
                .runId(run.getId())
                .caseId(evalCase.getId())
                .caseKey(evalCase.getCaseKey())
                .query(evalCase.getQuery())
                .passed(true)
                .failureReasons(List.of("none"))
                .hitCount(3)
                .expectedFileName("guide.md")
                .matchedFileName("guide.md")
                .matchedToken("token")
                .retrievalMode("hybrid_rerank")
                .fallbackReason("none")
                .embeddingModel("bge-m3")
                .embeddingDimension(1024)
                .topK(8)
                .build();
        invoke(result, "onCreate");
        assertThat(result.getId()).isNotNull();
        assertThat(result.getRunId()).isEqualTo(run.getId());
        assertThat(result.getCaseId()).isEqualTo(evalCase.getId());
        assertThat(result.getCaseKey()).isEqualTo("case-1");
        assertThat(result.getQuery()).isEqualTo("query");
        assertThat(result.isPassed()).isTrue();
        assertThat(result.getFailureReasons()).containsExactly("none");
        assertThat(result.getHitCount()).isEqualTo(3);
        assertThat(result.getExpectedFileName()).isEqualTo("guide.md");
        assertThat(result.getMatchedFileName()).isEqualTo("guide.md");
        assertThat(result.getMatchedToken()).isEqualTo("token");
        assertThat(result.getRetrievalMode()).isEqualTo("hybrid_rerank");
        assertThat(result.getFallbackReason()).isEqualTo("none");
        assertThat(result.getEmbeddingModel()).isEqualTo("bge-m3");
        assertThat(result.getEmbeddingDimension()).isEqualTo(1024);
        assertThat(result.getTopK()).isEqualTo(8);
        assertThat(result.getCreatedAt()).isNotNull();
    }

    private static void invoke(Object target, String methodName) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        method.invoke(target);
    }
}
