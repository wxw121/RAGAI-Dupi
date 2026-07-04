package com.dupi.rag.dto;

import com.dupi.rag.domain.enums.IngestJobStatus;
import com.dupi.rag.domain.enums.IngestStage;
import com.dupi.rag.domain.enums.ChunkStrategy;
import com.dupi.rag.domain.enums.DocumentStatus;
import com.dupi.rag.domain.enums.RetrievalMode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DtoCoverageTest {

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
}
