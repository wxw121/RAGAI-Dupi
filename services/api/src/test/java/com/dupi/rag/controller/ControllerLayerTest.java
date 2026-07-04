package com.dupi.rag.controller;

import com.dupi.rag.domain.entity.Chunk;
import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.dto.*;
import com.dupi.rag.repository.ChunkRepository;
import com.dupi.rag.repository.DocumentRepository;
import com.dupi.rag.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.mock.web.MockMultipartFile;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ControllerLayerTest {

    @Test
    void documentControllerDelegatesAllRoutesAndValidatesIngestJobOwnership() {
        DocumentService documentService = mock(DocumentService.class);
        IngestJobService ingestJobService = mock(IngestJobService.class);
        DocumentController controller = new DocumentController(documentService, ingestJobService);
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        MockMultipartFile file = new MockMultipartFile("file", "a.txt", "text/plain", "x".getBytes());
        DocumentResponse docResponse = DocumentResponse.builder().id(docId).kbId(kbId).fileName("a.txt").build();
        IngestJobResponse jobResponse = IngestJobResponse.builder().docId(docId).build();
        when(documentService.upload(kbId, file)).thenReturn(docResponse);
        when(documentService.listByKb(kbId)).thenReturn(List.of(docResponse));
        when(documentService.get(kbId, docId)).thenReturn(docResponse);
        when(ingestJobService.getLatestByDoc(docId)).thenReturn(jobResponse);

        assertThat(controller.upload(kbId, file)).isSameAs(docResponse);
        assertThat(controller.list(kbId)).containsExactly(docResponse);
        assertThat(controller.get(kbId, docId)).isSameAs(docResponse);
        controller.delete(kbId, docId);
        assertThat(controller.getIngestJob(kbId, docId)).isSameAs(jobResponse);

        verify(documentService).findOrThrow(kbId, docId);
        verify(documentService).delete(kbId, docId);
    }

    @Test
    void ingestCallbackControllerDelegatesStatusAndRetry() {
        IngestJobService service = mock(IngestJobService.class);
        IngestCallbackController controller = new IngestCallbackController(service);
        UUID jobId = UUID.randomUUID();
        IngestStatusUpdate update = IngestStatusUpdate.builder().jobId(jobId.toString()).build();
        IngestJobResponse response = IngestJobResponse.builder().id(jobId).build();
        when(service.retry(jobId)).thenReturn(response);

        assertThat(controller.updateStatus(update)).containsEntry("status", "ok");
        assertThat(controller.retry(jobId)).isSameAs(response);
        verify(service).handleStatusUpdate(update);
    }

    @Test
    void knowledgeBaseControllerDelegatesCrudRetrieveChatCancelAndJobs() {
        KnowledgeBaseService kbService = mock(KnowledgeBaseService.class);
        RetrievalService retrievalService = mock(RetrievalService.class);
        ChatService chatService = mock(ChatService.class);
        IngestJobService ingestJobService = mock(IngestJobService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        KnowledgeBaseController controller = new KnowledgeBaseController(kbService, retrievalService, chatService,
                ingestJobService, chatSessionService);
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
        when(chatSessionService.list(kbId)).thenReturn(List.of(sessionResponse));
        when(chatSessionService.create(kbId, createSession)).thenReturn(sessionResponse);
        when(chatSessionService.getDetail(kbId, sessionId)).thenReturn(sessionDetail);
        when(chatSessionService.rename(kbId, sessionId, renameSession)).thenReturn(sessionResponse);

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
        assertThat(controller.listChatSessions(kbId)).containsExactly(sessionResponse);
        assertThat(controller.createChatSession(kbId, createSession)).isSameAs(sessionResponse);
        assertThat(controller.getChatSession(kbId, sessionId)).isSameAs(sessionDetail);
        assertThat(controller.renameChatSession(kbId, sessionId, renameSession)).isSameAs(sessionResponse);
        controller.deleteChatSession(kbId, sessionId);
        controller.batchDeleteChatSessions(kbId, batchDeleteSessions);

        verify(kbService).delete(kbId);
        verify(chatService).cancel("s1");
        verify(chatService, never()).cancel(null);
        verify(chatSessionService).delete(kbId, sessionId);
        verify(chatSessionService).batchDelete(kbId, List.of(sessionId, secondSessionId));
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
        verify(kbService).findOrThrow(kbId);
    }
}
