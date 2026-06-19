package com.dupi.rag.service;

import com.dupi.rag.client.MilvusVectorService;
import com.dupi.rag.config.LlmProperties;
import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.dto.CreateKnowledgeBaseRequest;
import com.dupi.rag.dto.KnowledgeBaseResponse;
import com.dupi.rag.exception.ResourceNotFoundException;
import com.dupi.rag.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private final KnowledgeBaseRepository repository;
    private final MilvusVectorService milvusVectorService;
    private final LlmProperties llmProperties;

    @Transactional
    public KnowledgeBaseResponse create(CreateKnowledgeBaseRequest request) {
        String embeddingModel = request.getEmbeddingModel() != null
                ? request.getEmbeddingModel()
                : llmProperties.getEmbedding().getModel();
        Integer embeddingDimension = request.getEmbeddingDimension() != null
                ? request.getEmbeddingDimension()
                : llmProperties.getEmbedding().getDimension();

        KnowledgeBase kb = KnowledgeBase.builder()
                .name(request.getName())
                .description(request.getDescription())
                .chunkSize(request.getChunkSize())
                .chunkOverlap(request.getChunkOverlap())
                .topK(request.getTopK())
                .embeddingModel(embeddingModel)
                .embeddingDimension(embeddingDimension)
                .chunkStrategy(request.getChunkStrategy())
                .retrievalMode(request.getRetrievalMode())
                .build();
        return toResponse(repository.save(kb));
    }

    public KnowledgeBaseResponse get(UUID id) {
        return toResponse(findOrThrow(id));
    }

    public List<KnowledgeBaseResponse> list() {
        return repository.findAll().stream().map(this::toResponse).toList();
    }

    @Transactional
    public void delete(UUID id) {
        findOrThrow(id);
        milvusVectorService.deleteByKbId(id);
        repository.deleteById(id);
    }

    public KnowledgeBase findOrThrow(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Knowledge base not found: " + id));
    }

    private KnowledgeBaseResponse toResponse(KnowledgeBase kb) {
        return KnowledgeBaseResponse.builder()
                .id(kb.getId())
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
                .createdAt(kb.getCreatedAt())
                .updatedAt(kb.getUpdatedAt())
                .build();
    }
}
