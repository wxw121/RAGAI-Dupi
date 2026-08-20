package com.dupi.rag.service;

import com.dupi.rag.config.LlmProperties;
import com.dupi.rag.config.SecurityContext;
import com.dupi.rag.config.TenantContext;
import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.domain.enums.KnowledgeBaseLifecycleStatus;
import com.dupi.rag.domain.enums.RagEvalGateStatus;
import com.dupi.rag.domain.enums.RetrievalProfile;
import com.dupi.rag.dto.CreateKnowledgeBaseRequest;
import com.dupi.rag.dto.KnowledgeBaseResponse;
import com.dupi.rag.dto.OperationJobResponse;
import com.dupi.rag.dto.RagEvalGateDecisionResponse;
import com.dupi.rag.exception.ResourceNotFoundException;
import com.dupi.rag.exception.OperationConflictException;
import com.dupi.rag.exception.RetrievalProfileConflictException;
import com.dupi.rag.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private final KnowledgeBaseRepository repository;
    private final LlmProperties llmProperties;
    private final AuditLogService auditLogService;
    private final ProfileIndexStateService profileIndexStateService;
    private final RetrievalProfileGateService retrievalProfileGateService;
    private final KnowledgeBaseMaintenanceService maintenanceService;
    private final KnowledgeBaseDeletionPersistenceService deletionPersistence;
    private final OperationJobService operationJobs;

    @Transactional
    public KnowledgeBaseResponse create(CreateKnowledgeBaseRequest request) {
        if (request.getRetrievalProfile() != null && request.getRetrievalProfile() != RetrievalProfile.CLASSIC) {
            throw RetrievalProfileConflictException.gateBlocked(RagEvalGateDecisionResponse.builder()
                    .candidate(request.getRetrievalProfile())
                    .baseline(RetrievalProfile.CLASSIC)
                    .status(RagEvalGateStatus.NOT_EVALUATED)
                    .reason("not_evaluated")
                    .build());
        }
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
                .retrievalProfile(RetrievalProfile.CLASSIC)
                .tenantId(TenantContext.getTenantId())
                .build();
        return toResponse(repository.save(kb));
    }

    public KnowledgeBaseResponse get(UUID id) {
        return toResponse(findOrThrow(id));
    }

    @Transactional
    public KnowledgeBaseResponse updateRetrievalProfile(UUID id, RetrievalProfile retrievalProfile) {
        maintenanceService.assertMutationAllowed(id);
        KnowledgeBase kb = findForUpdateOrThrow(id);
        RetrievalProfile target = retrievalProfile == null ? RetrievalProfile.CLASSIC : retrievalProfile;
        if (target != RetrievalProfile.CLASSIC) {
            retrievalProfileGateService.assertCanActivate(id, target);
        }
        kb.setRetrievalProfile(target);
        KnowledgeBase saved = repository.save(kb);
        auditLogService.recordSuccessInCurrentTransaction(
                "KNOWLEDGE_BASE_RETRIEVAL_PROFILE_UPDATE",
                "KNOWLEDGE_BASE",
                id,
                "Updated retrieval profile to " + target.name());
        return toResponse(saved);
    }

    public List<KnowledgeBaseResponse> list() {
        return repository.findByTenantIdOrderByCreatedAtDesc(TenantContext.getTenantId()).stream()
                .map(this::toResponse)
                .toList();
    }

    public OperationJobResponse submitDelete(UUID id) {
        String actor = SecurityContext.getPrincipal();
        return submitDelete(id, actor == null || actor.isBlank() ? "system" : actor);
    }

    public OperationJobResponse submitDelete(UUID id, String actor) {
        UUID jobId = deletionPersistence.submit(id, TenantContext.getTenantId(), actor);
        return operationJobs.get(jobId);
    }

    public KnowledgeBase findOrThrow(UUID id) {
        KnowledgeBase knowledgeBase = repository.findByIdAndTenantIdAnyStatus(id, TenantContext.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Knowledge base not found: " + id));
        if (knowledgeBase.getLifecycleStatus() == KnowledgeBaseLifecycleStatus.DELETING) {
            throw new OperationConflictException(
                    "Knowledge base deletion is in progress; inspect the deletion operation status");
        }
        if (knowledgeBase.getLifecycleStatus() != KnowledgeBaseLifecycleStatus.READY) {
            throw new ResourceNotFoundException("Knowledge base not found: " + id);
        }
        return knowledgeBase;
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
        UUID kbId = kb.getId();
        boolean profileIndexReady = kbId != null && profileIndexStateService.isV2Ready(kbId);
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
                .indexSchemaVersion(ProfileIndexStateService.TARGET_SCHEMA_VERSION)
                .profileIndexReady(profileIndexReady)
                .indexRevision(kb.getIndexRevision() == null ? 0L : kb.getIndexRevision())
                .retrievalProfileGateDecisions(latestGateDecisions(kbId))
                .createdAt(kb.getCreatedAt())
                .updatedAt(kb.getUpdatedAt())
                .build();
    }

    private Map<RetrievalProfile, RagEvalGateDecisionResponse> latestGateDecisions(UUID kbId) {
        if (kbId == null) {
            return Map.of();
        }
        Map<RetrievalProfile, RagEvalGateDecisionResponse> decisions = new LinkedHashMap<>();
        for (RetrievalProfile profile : List.of(
                RetrievalProfile.PARENT_CHILD,
                RetrievalProfile.QA_ASSISTED,
                RetrievalProfile.COMBINED
        )) {
            RagEvalGateDecisionResponse decision = retrievalProfileGateService.latestDecision(kbId, profile);
            if (decision != null) {
                decisions.put(profile, decision);
            }
        }
        return decisions;
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
