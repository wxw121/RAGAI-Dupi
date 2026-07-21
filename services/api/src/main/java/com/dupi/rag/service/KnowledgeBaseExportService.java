package com.dupi.rag.service;

import com.dupi.rag.domain.entity.Chunk;
import com.dupi.rag.domain.entity.Document;
import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.domain.entity.RagEvalCase;
import com.dupi.rag.dto.CreateKnowledgeBaseRequest;
import com.dupi.rag.dto.KnowledgeBaseExportResponse;
import com.dupi.rag.dto.KnowledgeBaseImportRequest;
import com.dupi.rag.dto.KnowledgeBaseResponse;
import com.dupi.rag.dto.RagEvalCaseRequest;
import com.dupi.rag.dto.RagEvalCaseResponse;
import com.dupi.rag.repository.ChunkRepository;
import com.dupi.rag.repository.DocumentRepository;
import com.dupi.rag.repository.RagEvalCaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class KnowledgeBaseExportService {

    private static final int MAX_EXPORTED_CHUNKS = 10_000;
    private static final int MAX_EXPORTED_DOCUMENTS = 1_000;

    private final KnowledgeBaseService knowledgeBaseService;
    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;
    private final RagEvalCaseRepository ragEvalCaseRepository;
    private final RagEvalService ragEvalService;

    @Transactional(readOnly = true)
    public KnowledgeBaseExportResponse exportKnowledgeBase(UUID kbId) {
        KnowledgeBase kb = knowledgeBaseService.findOrThrow(kbId);
        List<Chunk> chunks = chunkRepository.findTop10001ByKbIdOrderByChunkIndexAsc(kbId);
        if (chunks.size() > MAX_EXPORTED_CHUNKS) {
            throw new IllegalArgumentException(
                    "Knowledge base export supports at most " + MAX_EXPORTED_CHUNKS + " chunk snapshots");
        }
        List<Document> documents = documentRepository.findTop1001ByKbIdOrderByCreatedAtDesc(kbId);
        if (documents.size() > MAX_EXPORTED_DOCUMENTS) {
            throw new IllegalArgumentException(
                    "Knowledge base export supports at most " + MAX_EXPORTED_DOCUMENTS + " document snapshots");
        }
        return KnowledgeBaseExportResponse.builder()
                .knowledgeBase(KnowledgeBaseExportResponse.KnowledgeBaseSnapshot.builder()
                        .originalId(kb.getId())
                        .tenantId(kb.getTenantId())
                        .name(kb.getName())
                        .description(kb.getDescription())
                        .chunkSize(kb.getChunkSize())
                        .chunkOverlap(kb.getChunkOverlap())
                        .topK(kb.getTopK())
                        .embeddingModel(kb.getEmbeddingModel())
                        .embeddingDimension(kb.getEmbeddingDimension())
                        .chunkStrategy(kb.getChunkStrategy())
                        .retrievalMode(kb.getRetrievalMode())
                        .build())
                .documents(documents.stream()
                        .map(this::toDocumentSnapshot)
                        .toList())
                .chunks(chunks.stream()
                        .map(this::toChunkSnapshot)
                        .toList())
                .evalCases(ragEvalCaseRepository.findByKbIdOrderByCreatedAtAsc(kbId).stream()
                        .map(this::toCaseResponse)
                        .toList())
                .exportedAt(Instant.now())
                .build();
    }

    @Transactional
    public KnowledgeBaseResponse restore(KnowledgeBaseImportRequest request) {
        if (request == null || request.getKnowledgeBase() == null) {
            throw new IllegalArgumentException("knowledge base snapshot is required");
        }
        var snapshot = request.getKnowledgeBase();
        CreateKnowledgeBaseRequest createRequest = new CreateKnowledgeBaseRequest();
        createRequest.setName(snapshot.getName().trim());
        createRequest.setDescription(snapshot.getDescription());
        createRequest.setChunkSize(snapshot.getChunkSize());
        createRequest.setChunkOverlap(snapshot.getChunkOverlap());
        createRequest.setTopK(snapshot.getTopK());
        createRequest.setEmbeddingModel(snapshot.getEmbeddingModel());
        createRequest.setEmbeddingDimension(snapshot.getEmbeddingDimension());
        createRequest.setChunkStrategy(snapshot.getChunkStrategy());
        createRequest.setRetrievalMode(snapshot.getRetrievalMode());
        KnowledgeBaseResponse restored = knowledgeBaseService.create(createRequest);

        List<RagEvalCaseRequest> evalCases = request.getEvalCases() == null ? List.of() : request.getEvalCases();
        for (RagEvalCaseRequest evalCase : evalCases) {
            ragEvalService.createCase(restored.getId(), evalCase);
        }
        return restored;
    }

    private KnowledgeBaseExportResponse.DocumentSnapshot toDocumentSnapshot(Document doc) {
        return KnowledgeBaseExportResponse.DocumentSnapshot.builder()
                .originalId(doc.getId())
                .fileName(doc.getFileName())
                .objectKey(doc.getObjectKey())
                .mimeType(doc.getMimeType())
                .fileSize(doc.getFileSize())
                .status(doc.getStatus())
                .errorMessage(doc.getErrorMessage())
                .build();
    }

    private KnowledgeBaseExportResponse.ChunkSnapshot toChunkSnapshot(Chunk chunk) {
        return KnowledgeBaseExportResponse.ChunkSnapshot.builder()
                .originalId(chunk.getId())
                .originalDocId(chunk.getDocId())
                .chunkIndex(chunk.getChunkIndex())
                .content(chunk.getContent())
                .tokenCount(chunk.getTokenCount())
                .metadata(chunk.getMetadata() == null ? Map.of() : chunk.getMetadata())
                .milvusId(chunk.getMilvusId())
                .build();
    }

    private RagEvalCaseResponse toCaseResponse(RagEvalCase evalCase) {
        return RagEvalCaseResponse.builder()
                .id(evalCase.getId())
                .kbId(evalCase.getKbId())
                .caseKey(evalCase.getCaseKey())
                .query(evalCase.getQuery())
                .minHits(evalCase.getMinHits())
                .topK(evalCase.getTopK())
                .category(evalCase.getCategory())
                .expectedFileName(evalCase.getExpectedFileName())
                .expectedFileNames(evalCase.getExpectedFileNames())
                .mustContainAny(evalCase.getMustContainAny())
                .createdAt(evalCase.getCreatedAt())
                .updatedAt(evalCase.getUpdatedAt())
                .build();
    }

}
