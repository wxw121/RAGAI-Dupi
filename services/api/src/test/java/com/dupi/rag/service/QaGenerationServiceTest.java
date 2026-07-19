package com.dupi.rag.service;

import com.dupi.rag.client.LlmClient;
import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.dto.QaCandidatesRequest;
import com.dupi.rag.repository.DocumentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QaGenerationServiceTest {

    @Mock
    KnowledgeBaseService knowledgeBaseService;
    @Mock
    DocumentRepository documentRepository;
    @Mock
    LlmClient llmClient;

    QaGenerationService service;

    @BeforeEach
    void setUp() {
        service = new QaGenerationService(knowledgeBaseService, documentRepository, llmClient, new ObjectMapper());
    }

    @Test
    void generateValidatesDeduplicatesAndCapsCandidatesPerSource() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        when(knowledgeBaseService.findSystemOrThrow(kbId)).thenReturn(KnowledgeBase.builder().id(kbId).build());
        when(documentRepository.findById(docId)).thenReturn(java.util.Optional.of(Document.builder()
                .id(docId)
                .kbId(kbId)
                .build()));
        when(llmClient.chat(anyString(), anyString())).thenReturn("""
                {"candidates":[
                  {"sourceChunkId":"%s","question":"What is alpha?","answer":"Alpha answer"},
                  {"sourceChunkId":"%s","question":"  WHAT   IS ALPHA? ","answer":"duplicate"},
                  {"sourceChunkId":"%s","question":"","answer":"blank question"},
                  {"sourceChunkId":"%s","question":"What is beta?","answer":"Beta answer"},
                  {"sourceChunkId":"%s","question":"What is gamma?","answer":"Gamma answer"},
                  {"sourceChunkId":"%s","question":"What is delta?","answer":"Delta answer"}
                ]}
                """.formatted(sourceId, sourceId, sourceId, sourceId, sourceId, sourceId));

        QaCandidatesRequest request = request(docId, sourceId, "Source content");

        var response = service.generate(kbId, request);

        assertThat(response.getCandidates()).extracting("question")
                .containsExactly("What is alpha?", "What is beta?", "What is gamma?");
        assertThat(response.getCandidates()).allSatisfy(candidate -> {
            assertThat(candidate.getSourceChunkId()).isEqualTo(sourceId);
            assertThat(candidate.getAnswer()).isNotBlank();
        });
    }

    @Test
    void generateRejectsDocumentFromAnotherKnowledgeBaseBeforeCallingLlm() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        when(knowledgeBaseService.findSystemOrThrow(kbId)).thenReturn(KnowledgeBase.builder().id(kbId).build());
        when(documentRepository.findById(docId)).thenReturn(java.util.Optional.of(Document.builder()
                .id(docId)
                .kbId(UUID.randomUUID())
                .build()));

        assertThatThrownBy(() -> service.generate(kbId, request(docId, sourceId, "Source content")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("document");
    }

    @Test
    void generateRejectsMalformedLlmJson() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        when(knowledgeBaseService.findSystemOrThrow(kbId)).thenReturn(KnowledgeBase.builder().id(kbId).build());
        when(documentRepository.findById(docId)).thenReturn(java.util.Optional.of(Document.builder()
                .id(docId)
                .kbId(kbId)
                .build()));
        when(llmClient.chat(anyString(), anyString())).thenReturn("not-json");

        assertThatThrownBy(() -> service.generate(kbId, request(docId, sourceId, "Source content")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("QA candidate response");
    }

    private static QaCandidatesRequest request(UUID docId, UUID sourceId, String content) {
        QaCandidatesRequest.SourceChunk source = new QaCandidatesRequest.SourceChunk();
        source.setChunkId(sourceId);
        source.setContent(content);
        source.setMetadata(Map.of("heading", "Section"));
        QaCandidatesRequest request = new QaCandidatesRequest();
        request.setDocId(docId);
        request.setSources(List.of(source));
        return request;
    }
}
