package com.dupi.rag.service;

import com.dupi.rag.domain.entity.RetrievalProfile;
import com.dupi.rag.domain.entity.RagEvalRun;
import com.dupi.rag.domain.entity.SparseMigration;
import com.dupi.rag.domain.enums.RagEvalRunStatus;
import com.dupi.rag.domain.enums.RagQualityGateStatus;
import com.dupi.rag.domain.enums.SparseMigrationState;
import com.dupi.rag.dto.SparseMigrationResponse;
import com.dupi.rag.dto.SparseMigrationValidationRequest;
import com.dupi.rag.exception.ResourceNotFoundException;
import com.dupi.rag.repository.ChunkRepository;
import com.dupi.rag.repository.RagEvalRunRepository;
import com.dupi.rag.repository.RetrievalProfileRepository;
import com.dupi.rag.repository.SparseMigrationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.data.domain.PageRequest;

import java.util.*;

@Service
@RequiredArgsConstructor
public class SparseMigrationService {
    private final SparseMigrationRepository repository;
    private final RetrievalProfileRepository profileRepository;
    private final RagEvalRunRepository runRepository;
    private final ChunkRepository chunkRepository;
    private final KnowledgeBaseService knowledgeBaseService;
    private final AuditLogService auditLogService;
    private final RetrievalProfileService retrievalProfileService;
    private final WebClient.Builder webClientBuilder;
    private final KnowledgeBaseMaintenanceService maintenanceService;

    @Value("${dupi.worker.base-url:http://localhost:8000}")
    private String workerBaseUrl;

    @Transactional
    public SparseMigrationResponse start(UUID kbId, UUID profileId) {
        maintenanceService.assertMutationAllowed(kbId);
        knowledgeBaseService.findForUpdateOrThrow(kbId);
        profile(kbId, profileId);
        SparseMigration migration = SparseMigration.builder().kbId(kbId).profileId(profileId)
                .state(SparseMigrationState.PREPARING).build();
        SparseMigration saved = repository.save(migration);
        auditLogService.recordSuccessInCurrentTransaction("SPARSE_MIGRATION_START", "KNOWLEDGE_BASE", kbId,
                "Started sparse migration for profile " + profileId);
        return SparseMigrationResponse.from(saved == null ? migration : saved);
    }

    @Transactional(readOnly = true)
    public List<SparseMigrationResponse> list(UUID kbId) {
        knowledgeBaseService.findOrThrow(kbId);
        return repository.findByKbIdOrderByCreatedAtDesc(kbId).stream().map(SparseMigrationResponse::from).toList();
    }

    public SparseMigrationResponse backfill(UUID kbId, UUID migrationId) {
        maintenanceService.assertMutationAllowed(kbId);
        SparseMigration migration = repository.findUnlockedByIdAndKbId(migrationId, kbId)
                .orElseThrow(() -> new ResourceNotFoundException("Sparse migration not found: " + migrationId));
        requireState(migration, SparseMigrationState.PREPARING, SparseMigrationState.BACKFILLING,
                SparseMigrationState.FAILED);
        RetrievalProfile profile = profile(kbId, migration.getProfileId());
        int expectedDimension = knowledgeBaseService.findOrThrow(kbId).getEmbeddingDimension();
        migration.setState(SparseMigrationState.BACKFILLING);
        migration = repository.save(migration);
        long sourceCount = chunkRepository.countByKbId(kbId);
        migration.setSourceChunkCount(sourceCount);
        migration = repository.save(migration);
        try {
            long submitted = 0;
            long collectionCount = -1;
            int page = 0;
            while (true) {
                var chunks = chunkRepository.findByKbIdOrderByIdAsc(kbId, PageRequest.of(page++, 500));
                if (chunks.isEmpty()) break;
                Map<String, Object> payload = Map.of(
                        "kb_id", kbId.toString(), "profile_version", profile.getVersion(),
                        "embedding_dimension", expectedDimension,
                        "sparse_index_params", profile.getSparseIndexParams(),
                        "chunks", chunks.getContent().stream().map(chunk -> Map.of(
                                "chunk_id", chunk.getId().toString(), "doc_id", chunk.getDocId().toString(),
                                "content", chunk.getContent())).toList());
                @SuppressWarnings("unchecked")
                Map<String, Object> response = webClientBuilder.build().post()
                        .uri(workerBaseUrl + "/api/v1/retrieve/sparse/backfill")
                        .bodyValue(payload).retrieve().bodyToMono(Map.class).block();
                if (response == null || !(response.get("indexed_count") instanceof Number batchCount)
                        || batchCount.longValue() != chunks.getNumberOfElements()
                        || !(response.get("collection_count") instanceof Number actualCount)
                        || !(response.get("verified_dimension") instanceof Number verifiedDimension)
                        || verifiedDimension.intValue() != expectedDimension) {
                    throw new IllegalStateException("Sparse backfill batch acknowledgement is invalid");
                }
                submitted += batchCount.longValue();
                collectionCount = actualCount.longValue();
                if (!chunks.hasNext()) break;
            }
            if (submitted != sourceCount || collectionCount != sourceCount) {
                throw new IllegalStateException("Sparse backfill coverage is incomplete");
            }
            migration.setIndexedChunkCount(collectionCount);
            migration.setExpectedDimension(expectedDimension);
            migration.setActualDimension(expectedDimension);
            migration.setState(SparseMigrationState.DUAL_WRITING);
            migration.setErrorMessage(null);
        } catch (RuntimeException ex) {
            migration.setState(SparseMigrationState.FAILED);
            migration.setErrorMessage(ex.getMessage());
            repository.save(migration);
            return SparseMigrationResponse.from(migration);
        }
        repository.save(migration);
        return SparseMigrationResponse.from(migration);
    }

    @Transactional
    public SparseMigrationResponse beginShadowValidation(UUID kbId, UUID migrationId) {
        maintenanceService.assertMutationAllowed(kbId);
        SparseMigration migration = migration(kbId, migrationId);
        requireState(migration, SparseMigrationState.DUAL_WRITING);
        migration.setState(SparseMigrationState.SHADOW_VALIDATING);
        return SparseMigrationResponse.from(repository.save(migration));
    }

    @Transactional
    public SparseMigrationResponse recordShadowValidation(
            UUID kbId, UUID migrationId, SparseMigrationValidationRequest request) {
        maintenanceService.assertMutationAllowed(kbId);
        SparseMigration migration = migration(kbId, migrationId);
        requireState(migration, SparseMigrationState.SHADOW_VALIDATING);
        RetrievalProfile profile = profile(kbId, migration.getProfileId());
        RagEvalRun candidateRun = passingRun(kbId, profile);
        if (candidateRun.getBaselineRunId() == null) {
            throw new IllegalArgumentException("Shadow validation requires a candidate run compared with a baseline");
        }
        RagEvalRun baselineRun = runRepository.findById(candidateRun.getBaselineRunId())
                .filter(run -> kbId.equals(run.getKbId()))
                .orElseThrow(() -> new IllegalArgumentException("Shadow validation baseline run is unavailable"));
        migration.setBaselineP95Ms(metric(baselineRun, "latencyP95Ms"));
        migration.setCandidateP95Ms(metric(candidateRun, "latencyP95Ms"));
        migration.setBaselineFallbackRate(fallbackRate(baselineRun));
        migration.setCandidateFallbackRate(fallbackRate(candidateRun));
        return SparseMigrationResponse.from(repository.save(migration));
    }

    @Transactional
    public SparseMigrationResponse cutover(UUID kbId, UUID migrationId) {
        maintenanceService.assertMutationAllowed(kbId);
        SparseMigration migration = migration(kbId, migrationId);
        requireState(migration, SparseMigrationState.SHADOW_VALIDATING);
        RetrievalProfile profile = profile(kbId, migration.getProfileId());
        boolean passingProfile = runRepository.findByKbIdAndStatusAndGateStatus(
                        kbId, RagEvalRunStatus.COMPLETED, RagQualityGateStatus.PASS).stream()
                .anyMatch(run -> profile.snapshot().equals(run.getProfileSnapshot()));
        if (migration.getSourceChunkCount() == 0
                || !migration.getSourceChunkCount().equals(migration.getIndexedChunkCount())) {
            throw new IllegalArgumentException("Cutover requires 100% chunk coverage");
        }
        if (!Objects.equals(migration.getExpectedDimension(), migration.getActualDimension())) {
            throw new IllegalArgumentException("Cutover requires matching dimensions");
        }
        if (!passingProfile) throw new IllegalArgumentException("Cutover requires a passing candidate profile");
        if (migration.getBaselineP95Ms() == null || migration.getCandidateP95Ms() == null
                || migration.getBaselineFallbackRate() == null || migration.getCandidateFallbackRate() == null) {
            throw new IllegalArgumentException("Cutover requires recorded shadow validation evidence");
        }
        if (migration.getCandidateP95Ms() > migration.getBaselineP95Ms() * 1.25) {
            throw new IllegalArgumentException("Cutover candidate P95 exceeds 1.25x baseline");
        }
        if (migration.getCandidateFallbackRate() > migration.getBaselineFallbackRate()) {
            throw new IllegalArgumentException("Cutover candidate fallback rate increased");
        }
        migration.setState(SparseMigrationState.CUTOVER);
        retrievalProfileService.activate(kbId, profile.getId());
        auditLogService.recordSuccessInCurrentTransaction("SPARSE_MIGRATION_CUTOVER", "KNOWLEDGE_BASE", kbId,
                "Cut over sparse migration " + migrationId);
        return SparseMigrationResponse.from(repository.save(migration));
    }

    @Transactional
    public SparseMigrationResponse complete(UUID kbId, UUID migrationId) {
        maintenanceService.assertMutationAllowed(kbId);
        SparseMigration migration = migration(kbId, migrationId);
        requireState(migration, SparseMigrationState.CUTOVER);
        migration.setState(SparseMigrationState.COMPLETED);
        return SparseMigrationResponse.from(repository.save(migration));
    }

    @Transactional
    public SparseMigrationResponse setLegacyFallback(UUID kbId, UUID migrationId, boolean enabled) {
        maintenanceService.assertMutationAllowed(kbId);
        SparseMigration migration = migration(kbId, migrationId);
        requireState(migration, SparseMigrationState.DUAL_WRITING, SparseMigrationState.SHADOW_VALIDATING);
        migration.setLegacyBm25Enabled(enabled);
        auditLogService.recordSuccessInCurrentTransaction("LEGACY_BM25_FALLBACK_CHANGE", "KNOWLEDGE_BASE", kbId,
                "Legacy BM25 fallback " + (enabled ? "enabled" : "disabled") + " for migration " + migrationId);
        return SparseMigrationResponse.from(repository.save(migration));
    }

    private RetrievalProfile profile(UUID kbId, UUID profileId) {
        return profileRepository.findByIdAndKbId(profileId, kbId)
                .orElseThrow(() -> new ResourceNotFoundException("Retrieval profile not found: " + profileId));
    }

    private RagEvalRun passingRun(UUID kbId, RetrievalProfile profile) {
        return runRepository.findByKbIdAndStatusAndGateStatus(
                        kbId, RagEvalRunStatus.COMPLETED, RagQualityGateStatus.PASS).stream()
                .filter(run -> profile.snapshot().equals(run.getProfileSnapshot()))
                .max(Comparator.comparing(RagEvalRun::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElseThrow(() -> new IllegalArgumentException("Sparse migration requires a passing candidate profile"));
    }

    private double metric(RagEvalRun run, String key) {
        Object value = run.getMetrics().get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("Evaluation run is missing metric " + key);
        }
        return number.doubleValue();
    }

    private double fallbackRate(RagEvalRun run) {
        if (run.getTotalCount() == null || run.getTotalCount() <= 0) return 0.0;
        return metric(run, "fallbackCount") / run.getTotalCount();
    }

    private SparseMigration migration(UUID kbId, UUID id) {
        return repository.findByIdAndKbId(id, kbId)
                .orElseThrow(() -> new ResourceNotFoundException("Sparse migration not found: " + id));
    }

    private void requireState(SparseMigration migration, SparseMigrationState... allowed) {
        if (!Arrays.asList(allowed).contains(migration.getState())) {
            throw new IllegalArgumentException("Invalid sparse migration transition from " + migration.getState());
        }
    }
}
