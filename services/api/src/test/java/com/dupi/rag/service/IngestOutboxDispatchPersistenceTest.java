package com.dupi.rag.service;

import com.dupi.rag.config.RedisQueueProperties;
import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.IngestJob;
import com.dupi.rag.domain.entity.IngestOutboxEvent;
import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.domain.enums.DocumentStatus;
import com.dupi.rag.domain.enums.IngestJobStatus;
import com.dupi.rag.domain.enums.IngestOutboxStatus;
import com.dupi.rag.domain.enums.IngestStage;
import com.dupi.rag.dto.IngestJobMessage;
import com.dupi.rag.repository.DocumentRepository;
import com.dupi.rag.repository.IngestJobRepository;
import com.dupi.rag.repository.IngestOutboxDispatchCandidate;
import com.dupi.rag.repository.IngestOutboxEventRepository;
import com.dupi.rag.repository.RetrievalProfileRepository;
import com.dupi.rag.repository.SparseMigrationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IngestOutboxDispatchPersistenceTest {
    @Mock IngestOutboxEventRepository outbox;
    @Mock IngestJobRepository jobs;
    @Mock DocumentRepository documents;
    @Mock KnowledgeBaseService knowledgeBases;
    @Mock DocumentTombstoneService tombstones;

    @Test
    void claimPersistsTheSameExecutionIdThatTheRealProducerPublishes() throws Exception {
        Fixture fixture = fixture();
        fixture.job.setExecutionId(null);
        stubDue(fixture);

        IngestOutboxDispatchClaim claim = persistence().claimDue().get(0);

        assertThat(claim.executionId()).isNotNull().isEqualTo(fixture.job.getExecutionId());
        verify(jobs).save(fixture.job);
        InOrder order = inOrder(jobs, documents, outbox);
        order.verify(jobs).findByIdForUpdate(fixture.job.getId());
        order.verify(documents).findByIdForUpdate(fixture.document.getId());
        order.verify(outbox).findByIdForUpdate(fixture.event.getId());
        order.verify(jobs).save(fixture.job);
        order.verify(outbox).save(fixture.event);

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ListOperations<String, String> list = mock(ListOperations.class);
        when(redis.opsForList()).thenReturn(list);
        RedisQueueProperties queues = new RedisQueueProperties();
        queues.setIngestQueue("jobs");
        SparseMigrationRepository migrations = mock(SparseMigrationRepository.class);
        when(migrations.findTopByKbIdAndStateInOrderByCreatedAtDesc(eq(fixture.job.getKbId()), anyList()))
                .thenReturn(Optional.empty());
        ObjectMapper mapper = new ObjectMapper();
        IngestJobProducer producer = new IngestJobProducer(redis, queues, mapper,
                mock(ProfileIndexStateService.class), migrations, mock(RetrievalProfileRepository.class));

        producer.enqueue(claim.job(), claim.knowledgeBase(), "object", "file.md", "text/markdown",
                claim.executionId());

        var payload = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(list).leftPush(eq("jobs"), payload.capture());
        IngestJobMessage message = mapper.readValue(payload.getValue(), IngestJobMessage.class);
        assertThat(UUID.fromString(message.getExecutionId()))
                .isEqualTo(claim.executionId())
                .isEqualTo(fixture.job.getExecutionId());
    }

    @Test
    void staleCandidateCannotTakeOverAnUnexpiredDispatchClaim() {
        Fixture fixture = fixture();
        fixture.event.setLastError("Dispatch claim live-owner");
        fixture.event.setNextAttemptAt(Instant.now().plusSeconds(60));
        stubCandidateAndLocks(fixture);

        assertThat(persistence().claimDue()).isEmpty();

        verify(outbox, never()).save(any());
        verifyNoInteractions(knowledgeBases);
    }

    @Test
    void cancellationLocksOnlyEventAfterCallerOwnedJobAndDocumentAndRejectsLateCompletion() {
        Fixture fixture = fixture();
        stubDue(fixture);
        IngestOutboxDispatchClaim claim = persistence().claimDue().get(0);
        clearInvocations(jobs, documents, outbox);
        when(outbox.findIdsByJobIdAndStatusIn(eq(fixture.job.getId()), any())).thenReturn(List.of(fixture.event.getId()));
        when(outbox.findByIdForUpdate(fixture.event.getId())).thenReturn(Optional.of(fixture.event));

        IngestOutboxDispatchPersistence persistence = persistence();
        persistence.cancelPendingForJob(fixture.job.getId(), "cancelled by user");

        InOrder order = inOrder(outbox);
        order.verify(outbox).findIdsByJobIdAndStatusIn(eq(fixture.job.getId()), any());
        order.verify(outbox).findByIdForUpdate(fixture.event.getId());
        verifyNoInteractions(jobs, documents);
        assertThat(fixture.event.getStatus()).isEqualTo(IngestOutboxStatus.CANCELLED);

        when(jobs.findByIdForUpdate(fixture.job.getId())).thenReturn(Optional.of(fixture.job));
        when(documents.findByIdForUpdate(fixture.document.getId())).thenReturn(Optional.of(fixture.document));
        assertThat(persistence.complete(claim)).isFalse();
        assertThat(fixture.event.getStatus()).isEqualTo(IngestOutboxStatus.CANCELLED);
    }

    private void stubDue(Fixture fixture) {
        stubCandidateAndLocks(fixture);
        when(knowledgeBases.findSystemOrThrow(fixture.event.getKbId())).thenReturn(fixture.knowledgeBase);
    }

    private void stubCandidateAndLocks(Fixture fixture) {
        when(outbox.findDispatchCandidates(eq(List.of(IngestOutboxStatus.PENDING, IngestOutboxStatus.FAILED)),
                any(Instant.class), any())).thenReturn(List.of(new IngestOutboxDispatchCandidate(
                fixture.event.getId(), fixture.job.getId(), fixture.document.getId())));
        when(jobs.findByIdForUpdate(fixture.job.getId())).thenReturn(Optional.of(fixture.job));
        when(documents.findByIdForUpdate(fixture.document.getId())).thenReturn(Optional.of(fixture.document));
        when(outbox.findByIdForUpdate(fixture.event.getId())).thenReturn(Optional.of(fixture.event));
    }

    private IngestOutboxDispatchPersistence persistence() {
        return new IngestOutboxDispatchPersistence(outbox, jobs, documents, knowledgeBases, tombstones);
    }

    private static Fixture fixture() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        IngestJob job = IngestJob.builder().id(jobId).kbId(kbId).docId(docId)
                .executionId(UUID.randomUUID()).status(IngestJobStatus.PENDING).stage(IngestStage.QUEUED).build();
        Document document = Document.builder().id(docId).kbId(kbId).status(DocumentStatus.PENDING).build();
        IngestOutboxEvent event = IngestOutboxEvent.builder().id(UUID.randomUUID()).jobId(jobId).kbId(kbId)
                .docId(docId).status(IngestOutboxStatus.PENDING).attemptCount(0)
                .nextAttemptAt(Instant.now().minusSeconds(1)).createdAt(Instant.now()).build();
        return new Fixture(job, document, event, KnowledgeBase.builder().id(kbId)
                .chunkSize(300).chunkOverlap(30)
                .chunkStrategy(com.dupi.rag.domain.enums.ChunkStrategy.MARKDOWN)
                .retrievalProfile(com.dupi.rag.domain.enums.RetrievalProfile.COMBINED)
                .embeddingModel("embed").embeddingDimension(384).build());
    }

    private record Fixture(IngestJob job, Document document, IngestOutboxEvent event,
                           KnowledgeBase knowledgeBase) { }
}
