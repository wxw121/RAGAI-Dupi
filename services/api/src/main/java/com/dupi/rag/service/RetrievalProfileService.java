package com.dupi.rag.service;

import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.domain.entity.RetrievalProfile;
import com.dupi.rag.domain.enums.RagEvalRunStatus;
import com.dupi.rag.domain.enums.RagQualityGateStatus;
import com.dupi.rag.dto.RetrievalProfileRequest;
import com.dupi.rag.dto.RetrievalProfileResponse;
import com.dupi.rag.exception.ResourceNotFoundException;
import com.dupi.rag.repository.RagEvalRunRepository;
import com.dupi.rag.repository.RetrievalProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RetrievalProfileService {
    private final RetrievalProfileRepository profileRepository;
    private final RagEvalRunRepository runRepository;
    private final KnowledgeBaseService knowledgeBaseService;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public List<RetrievalProfileResponse> list(UUID kbId) {
        KnowledgeBase kb = knowledgeBaseService.findOrThrow(kbId);
        return profileRepository.findByKbIdOrderByVersionDesc(kbId).stream()
                .map(profile -> toResponse(profile, profile.getId().equals(kb.getActiveRetrievalProfileId())))
                .toList();
    }

    @Transactional
    public RetrievalProfileResponse create(UUID kbId, RetrievalProfileRequest request) {
        rejectSensitiveParameters(request.getSparseIndexParams());
        rejectSensitiveParameters(request.getSparseSearchParams());
        knowledgeBaseService.findForUpdateOrThrow(kbId);
        int nextVersion = profileRepository.findTopByKbIdOrderByVersionDesc(kbId)
                .map(profile -> profile.getVersion() + 1).orElse(1);
        RetrievalProfile profile = RetrievalProfile.builder()
                .kbId(kbId)
                .name(request.getName().trim())
                .version(nextVersion)
                .vectorCandidateCount(request.getVectorCandidateCount())
                .sparseCandidateCount(request.getSparseCandidateCount())
                .rrfConstant(request.getRrfConstant())
                .sparseIndexParams(request.getSparseIndexParams() == null ? java.util.Map.of() : java.util.Map.copyOf(request.getSparseIndexParams()))
                .sparseSearchParams(request.getSparseSearchParams() == null ? java.util.Map.of() : java.util.Map.copyOf(request.getSparseSearchParams()))
                .rerankEnabled(request.getRerankEnabled())
                .rerankCandidateLimit(request.getRerankCandidateLimit())
                .finalTopK(request.getFinalTopK())
                .build();
        RetrievalProfile saved = profileRepository.save(profile);
        return toResponse(saved == null ? profile : saved, false);
    }

    @Transactional
    public RetrievalProfileResponse activate(UUID kbId, UUID profileId) {
        return changeActiveProfile(kbId, profileId, false);
    }

    @Transactional
    public RetrievalProfileResponse rollback(UUID kbId, UUID profileId) {
        return changeActiveProfile(kbId, profileId, true);
    }

    private RetrievalProfileResponse changeActiveProfile(UUID kbId, UUID profileId, boolean rollback) {
        RetrievalProfile profile = find(kbId, profileId);
        boolean hasPassingSnapshot = runRepository.findByKbIdAndStatusAndGateStatus(
                        kbId, RagEvalRunStatus.COMPLETED, RagQualityGateStatus.PASS).stream()
                .anyMatch(run -> profile.snapshot().equals(run.getProfileSnapshot()));
        if (!hasPassingSnapshot) {
            throw new IllegalArgumentException("Retrieval profile requires a matching passing quality gate");
        }
        KnowledgeBase kb = knowledgeBaseService.findForUpdateOrThrow(kbId);
        RetrievalProfile previous = kb.getActiveRetrievalProfileId() == null
                ? null : find(kbId, kb.getActiveRetrievalProfileId());
        if (!rollback && previous != null && profile.getVersion() < previous.getVersion()) {
            throw new IllegalArgumentException("Activating an older profile requires the rollback endpoint");
        }
        if (rollback && (previous == null || profile.getVersion() >= previous.getVersion())) {
            throw new IllegalArgumentException("Rollback requires an older previously passing profile");
        }
        kb.setActiveRetrievalProfileId(profileId);
        String action = rollback ? "RETRIEVAL_PROFILE_ROLLBACK" : "RETRIEVAL_PROFILE_ACTIVATE";
        String message = rollback
                ? "Rolled back retrieval profile from " + previous.getId() + " to " + profileId
                : "Activated retrieval profile " + profileId;
        auditLogService.recordSuccessInCurrentTransaction(action, "KNOWLEDGE_BASE", kbId, message);
        return toResponse(profile, true);
    }

    @Transactional(readOnly = true)
    public RetrievalProfile resolveActive(KnowledgeBase kb) {
        if (kb.getActiveRetrievalProfileId() == null) return null;
        return find(kb.getId(), kb.getActiveRetrievalProfileId());
    }

    @Transactional(readOnly = true)
    public RetrievalProfile find(UUID kbId, UUID profileId) {
        return profileRepository.findByIdAndKbId(profileId, kbId)
                .orElseThrow(() -> new ResourceNotFoundException("Retrieval profile not found: " + profileId));
    }

    private RetrievalProfileResponse toResponse(RetrievalProfile profile, boolean active) {
        return RetrievalProfileResponse.builder()
                .id(profile.getId()).kbId(profile.getKbId()).name(profile.getName()).version(profile.getVersion())
                .vectorCandidateCount(profile.getVectorCandidateCount())
                .sparseCandidateCount(profile.getSparseCandidateCount()).rrfConstant(profile.getRrfConstant())
                .sparseIndexParams(profile.getSparseIndexParams()).sparseSearchParams(profile.getSparseSearchParams())
                .rerankEnabled(profile.getRerankEnabled()).rerankCandidateLimit(profile.getRerankCandidateLimit())
                .finalTopK(profile.getFinalTopK()).active(active).createdAt(profile.getCreatedAt()).build();
    }

    private void rejectSensitiveParameters(Object value) {
        if (value instanceof java.util.Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey()).toLowerCase(java.util.Locale.ROOT);
                if (isSensitiveKey(key)) {
                    throw new IllegalArgumentException("Sparse parameters cannot contain sensitive configuration");
                }
                rejectSensitiveParameters(entry.getValue());
            }
        } else if (value instanceof Iterable<?> iterable) {
            iterable.forEach(this::rejectSensitiveParameters);
        }
    }

    private boolean isSensitiveKey(String key) {
        return key.contains("secret") || key.contains("password") || key.contains("credential")
                || key.contains("api_key") || key.contains("apikey") || key.contains("access_key")
                || key.contains("accesskey") || key.contains("authorization") || key.contains("token")
                || key.contains("url") || key.contains("endpoint");
    }
}
