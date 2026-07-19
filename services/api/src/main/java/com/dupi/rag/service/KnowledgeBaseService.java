package com.dupi.rag.service;

import com.dupi.rag.client.MilvusVectorService;
import com.dupi.rag.config.LlmProperties;
import com.dupi.rag.config.TenantContext;
import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.domain.enums.RetrievalProfile;
import com.dupi.rag.dto.CreateKnowledgeBaseRequest;
import com.dupi.rag.dto.KnowledgeBaseResponse;
import com.dupi.rag.exception.ResourceNotFoundException;
import com.dupi.rag.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeBaseService {

    private final KnowledgeBaseRepository repository;
    private final MilvusVectorService milvusVectorService;
    private final LlmProperties llmProperties;
    private final VectorCleanupTaskService vectorCleanupTaskService;
    private final AuditLogService auditLogService;

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
                .retrievalProfile(request.getRetrievalProfile())
                .tenantId(TenantContext.getTenantId())
                .build();
        return toResponse(repository.save(kb));
    }

    public KnowledgeBaseResponse get(UUID id) {
        return toResponse(findOrThrow(id));
    }

    @Transactional
    public KnowledgeBaseResponse updateRetrievalProfile(UUID id, RetrievalProfile retrievalProfile) {
        KnowledgeBase kb = findOrThrow(id);
        kb.setRetrievalProfile(retrievalProfile);
        return toResponse(repository.save(kb));
    }

    public List<KnowledgeBaseResponse> list() {
        return repository.findByTenantIdOrderByCreatedAtDesc(TenantContext.getTenantId()).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public void delete(UUID id) {
        findOrThrow(id);
        vectorCleanupTaskService.enqueueProfileKnowledgeBase(id);
        vectorCleanupTaskService.enqueueLegacyKnowledgeBase(id);
        boolean compensationRequired = false;
        try {
            milvusVectorService.deleteProfileByKbId(id);
        } catch (Exception e) {
            compensationRequired = true;
            log.warn("Failed to delete profile Milvus vectors for knowledge base {}", id, e);
        }
        try {
            milvusVectorService.deleteByKbId(id);
        } catch (Exception e) {
            compensationRequired = true;
            log.warn("Failed to delete Milvus vectors for knowledge base {}", id, e);
            // 知识库删除以数据库为最终事实源：外部向量库短暂不可用时不阻塞主记录删除，
            // 避免用户被 Milvus 半加载或不可用状态卡住；残留向量由补偿任务后续清理。
        }
        repository.deleteById(id);
        auditLogService.recordSuccess(
                "KNOWLEDGE_BASE_DELETE",
                "KNOWLEDGE_BASE",
                id,
                compensationRequired
                        ? "Deleted knowledge base " + id + " with vector cleanup compensation"
                        : "Deleted knowledge base " + id
        );
    }

    public KnowledgeBase findOrThrow(UUID id) {
        return repository.findByIdAndTenantId(id, TenantContext.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Knowledge base not found: " + id));
    }

    public KnowledgeBase findForUpdateOrThrow(UUID id) {
        return repository.findByIdAndTenantIdForUpdate(id, TenantContext.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Knowledge base not found: " + id));
    }

    public KnowledgeBase findSystemOrThrow(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Knowledge base not found: " + id));
    }

    private KnowledgeBaseResponse toResponse(KnowledgeBase kb) {
        boolean embeddingConfigCurrent = isEmbeddingConfigCurrent(kb);
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
                .embeddingConfigCurrent(embeddingConfigCurrent)
                .embeddingConfigWarning(embeddingConfigCurrent ? null : embeddingConfigWarning(kb))
                .chunkStrategy(kb.getChunkStrategy())
                .retrievalMode(kb.getRetrievalMode())
                .retrievalProfile(kb.getRetrievalProfile())
                .createdAt(kb.getCreatedAt())
                .updatedAt(kb.getUpdatedAt())
                .build();
    }

    private boolean isEmbeddingConfigCurrent(KnowledgeBase kb) {
        String currentModel = llmProperties.getEmbedding().getModel();
        int currentDimension = llmProperties.getEmbedding().getDimension();
        return java.util.Objects.equals(kb.getEmbeddingModel(), currentModel)
                && java.util.Objects.equals(kb.getEmbeddingDimension(), currentDimension);
    }

    private String embeddingConfigWarning(KnowledgeBase kb) {
        return "Knowledge base embedding config "
                + kb.getEmbeddingModel() + "/" + kb.getEmbeddingDimension()
                + " differs from current embedding config "
                + llmProperties.getEmbedding().getModel() + "/" + llmProperties.getEmbedding().getDimension()
                + "; re-index or recreate this knowledge base before relying on retrieval quality.";
    }
}
