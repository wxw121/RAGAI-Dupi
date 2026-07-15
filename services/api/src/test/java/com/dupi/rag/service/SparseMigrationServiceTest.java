package com.dupi.rag.service;

import com.dupi.rag.domain.entity.RagEvalRun;
import com.dupi.rag.domain.entity.RetrievalProfile;
import com.dupi.rag.domain.entity.SparseMigration;
import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.domain.entity.Chunk;
import com.dupi.rag.domain.enums.*;
import com.dupi.rag.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Map;
import java.time.Instant;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SparseMigrationServiceTest {
    @Mock SparseMigrationRepository repository;
    @Mock RetrievalProfileRepository profileRepository;
    @Mock RagEvalRunRepository runRepository;
    @Mock ChunkRepository chunkRepository;
    @Mock KnowledgeBaseService knowledgeBaseService;
    @Mock AuditLogService auditLogService;
    @Mock RetrievalProfileService retrievalProfileService;
    @Mock WebClient.Builder webClientBuilder;

    @Test
    void backfillSubmitsCanonicalChunksAndTransitionsToDualWrite() {
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        RetrievalProfile profile = profile(kbId);
        SparseMigration migration = SparseMigration.builder().id(UUID.randomUUID()).kbId(kbId)
                .profileId(profile.getId()).state(SparseMigrationState.PREPARING).build();
        Chunk chunk = Chunk.builder().id(UUID.randomUUID()).kbId(kbId).docId(docId).content("coverage cutover").build();
        when(repository.findUnlockedByIdAndKbId(migration.getId(), kbId)).thenReturn(Optional.of(migration));
        when(profileRepository.findByIdAndKbId(profile.getId(), kbId)).thenReturn(Optional.of(profile));
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(KnowledgeBase.builder()
                .id(kbId).embeddingDimension(1536).build());
        when(chunkRepository.countByKbId(kbId)).thenReturn(1L);
        when(chunkRepository.findByKbIdOrderByIdAsc(eq(kbId), any())).thenReturn(new PageImpl<>(List.of(chunk)));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        WebClient.Builder client = WebClient.builder().exchangeFunction(request -> Mono.just(
                ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json")
                        .body("{\"indexed_count\":1,\"collection_count\":1,\"verified_dimension\":1536}").build()));

        var response = service(client).backfill(kbId, migration.getId());

        assertThat(response.getState()).isEqualTo(SparseMigrationState.DUAL_WRITING);
        assertThat(response.getSourceChunkCount()).isEqualTo(1);
        assertThat(response.getIndexedChunkCount()).isEqualTo(1);
        assertThat(migration.getActualDimension()).isEqualTo(1536);
    }

    @Test
    void backfillRecordsFailureWhenWorkerAcknowledgementIsInvalid() {
        UUID kbId = UUID.randomUUID();
        RetrievalProfile profile = profile(kbId);
        SparseMigration migration = SparseMigration.builder().id(UUID.randomUUID()).kbId(kbId)
                .profileId(profile.getId()).state(SparseMigrationState.PREPARING).build();
        Chunk chunk = Chunk.builder().id(UUID.randomUUID()).kbId(kbId).docId(UUID.randomUUID()).content("x").build();
        when(repository.findUnlockedByIdAndKbId(migration.getId(), kbId)).thenReturn(Optional.of(migration));
        when(profileRepository.findByIdAndKbId(profile.getId(), kbId)).thenReturn(Optional.of(profile));
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(KnowledgeBase.builder()
                .id(kbId).embeddingDimension(8).build());
        when(chunkRepository.countByKbId(kbId)).thenReturn(1L);
        when(chunkRepository.findByKbIdOrderByIdAsc(eq(kbId), any())).thenReturn(new PageImpl<>(List.of(chunk)));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        WebClient.Builder client = WebClient.builder().exchangeFunction(request -> Mono.just(
                ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json")
                        .body("{\"indexed_count\":0}").build()));

        var response = service(client).backfill(kbId, migration.getId());

        assertThat(response.getState()).isEqualTo(SparseMigrationState.FAILED);
        assertThat(response.getErrorMessage()).contains("acknowledgement");
    }

    @Test
    void startAndListPersistAndScopeMigrations() {
        UUID kbId = UUID.randomUUID();
        RetrievalProfile profile = profile(kbId);
        when(profileRepository.findByIdAndKbId(profile.getId(), kbId)).thenReturn(Optional.of(profile));
        when(repository.save(any())).thenAnswer(invocation -> {
            SparseMigration value = invocation.getArgument(0);
            value.setId(UUID.randomUUID());
            return value;
        });

        var started = service().start(kbId, profile.getId());
        when(repository.findByKbIdOrderByCreatedAtDesc(kbId)).thenReturn(List.of(
                SparseMigration.builder().id(started.getId()).kbId(kbId).profileId(profile.getId())
                        .state(SparseMigrationState.PREPARING).build()));
        var listed = service().list(kbId);

        assertThat(started.getState()).isEqualTo(SparseMigrationState.PREPARING);
        assertThat(listed).extracting(item -> item.getId()).containsExactly(started.getId());
        verify(knowledgeBaseService).findForUpdateOrThrow(kbId);
        verify(knowledgeBaseService).findOrThrow(kbId);
        verify(auditLogService).recordSuccessInCurrentTransaction("SPARSE_MIGRATION_START",
                "KNOWLEDGE_BASE", kbId, "Started sparse migration for profile " + profile.getId());
    }

    @Test
    void migrationTransitionsAndLegacyFallbackAreAudited() {
        UUID kbId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        SparseMigration migration = SparseMigration.builder().id(UUID.randomUUID()).kbId(kbId)
                .profileId(profileId).state(SparseMigrationState.DUAL_WRITING).build();
        when(repository.findByIdAndKbId(migration.getId(), kbId)).thenReturn(Optional.of(migration));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service().setLegacyFallback(kbId, migration.getId(), true).getLegacyBm25Enabled()).isTrue();
        assertThat(service().beginShadowValidation(kbId, migration.getId()).getState())
                .isEqualTo(SparseMigrationState.SHADOW_VALIDATING);
        verify(auditLogService).recordSuccessInCurrentTransaction("LEGACY_BM25_FALLBACK_CHANGE",
                "KNOWLEDGE_BASE", kbId, "Legacy BM25 fallback enabled for migration " + migration.getId());

        migration.setState(SparseMigrationState.CUTOVER);
        assertThat(service().complete(kbId, migration.getId()).getState()).isEqualTo(SparseMigrationState.COMPLETED);
        assertThatThrownBy(() -> service().complete(kbId, migration.getId()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("COMPLETED");
    }

    @Test
    void shadowValidationRecordsBaselineAndCandidateMetrics() {
        UUID kbId = UUID.randomUUID();
        RetrievalProfile profile = profile(kbId);
        UUID baselineId = UUID.randomUUID();
        SparseMigration migration = SparseMigration.builder().id(UUID.randomUUID()).kbId(kbId)
                .profileId(profile.getId()).state(SparseMigrationState.SHADOW_VALIDATING).build();
        RagEvalRun candidate = RagEvalRun.builder().id(UUID.randomUUID()).kbId(kbId)
                .createdAt(Instant.now()).baselineRunId(baselineId).totalCount(4)
                .metrics(Map.of("latencyP95Ms", 110, "fallbackCount", 1))
                .profileSnapshot(profile.snapshot()).build();
        RagEvalRun baseline = RagEvalRun.builder().id(baselineId).kbId(kbId).totalCount(4)
                .metrics(Map.of("latencyP95Ms", 100, "fallbackCount", 1)).build();
        when(repository.findByIdAndKbId(migration.getId(), kbId)).thenReturn(Optional.of(migration));
        when(profileRepository.findByIdAndKbId(profile.getId(), kbId)).thenReturn(Optional.of(profile));
        when(runRepository.findByKbIdAndStatusAndGateStatus(kbId, RagEvalRunStatus.COMPLETED,
                RagQualityGateStatus.PASS)).thenReturn(List.of(candidate));
        when(runRepository.findById(baselineId)).thenReturn(Optional.of(baseline));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service().recordShadowValidation(kbId, migration.getId(), new com.dupi.rag.dto.SparseMigrationValidationRequest());

        assertThat(migration.getBaselineP95Ms()).isEqualTo(100);
        assertThat(migration.getCandidateP95Ms()).isEqualTo(110);
        assertThat(migration.getBaselineFallbackRate()).isEqualTo(0.25);
        assertThat(migration.getCandidateFallbackRate()).isEqualTo(0.25);
    }

    @Test
    void missingResourcesAndEvidenceAreRejected() {
        UUID kbId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndKbId(id, kbId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().beginShadowValidation(kbId, id))
                .isInstanceOf(com.dupi.rag.exception.ResourceNotFoundException.class);

        RetrievalProfile profile = profile(kbId);
        SparseMigration migration = SparseMigration.builder().id(id).kbId(kbId).profileId(profile.getId())
                .state(SparseMigrationState.SHADOW_VALIDATING).build();
        when(repository.findByIdAndKbId(id, kbId)).thenReturn(Optional.of(migration));
        when(profileRepository.findByIdAndKbId(profile.getId(), kbId)).thenReturn(Optional.of(profile));
        when(runRepository.findByKbIdAndStatusAndGateStatus(kbId, RagEvalRunStatus.COMPLETED,
                RagQualityGateStatus.PASS)).thenReturn(List.of());
        assertThatThrownBy(() -> service().recordShadowValidation(kbId, id,
                new com.dupi.rag.dto.SparseMigrationValidationRequest()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("passing candidate");
    }

    @Test
    void cutoverAcceptsOnlyCompletePassingNonRegressingCandidate() {
        UUID kbId = UUID.randomUUID();
        RetrievalProfile profile = profile(kbId);
        SparseMigration migration = migration(kbId, profile.getId());
        RagEvalRun pass = RagEvalRun.builder().kbId(kbId).status(RagEvalRunStatus.COMPLETED)
                .gateStatus(RagQualityGateStatus.PASS).profileSnapshot(profile.snapshot()).build();
        when(repository.findByIdAndKbId(migration.getId(), kbId)).thenReturn(Optional.of(migration));
        when(profileRepository.findByIdAndKbId(profile.getId(), kbId)).thenReturn(Optional.of(profile));
        when(runRepository.findByKbIdAndStatusAndGateStatus(
                kbId, RagEvalRunStatus.COMPLETED, RagQualityGateStatus.PASS)).thenReturn(List.of(pass));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service().cutover(kbId, migration.getId());

        assertThat(response.getState()).isEqualTo(SparseMigrationState.CUTOVER);
        verify(auditLogService).recordSuccessInCurrentTransaction(
                "SPARSE_MIGRATION_CUTOVER", "KNOWLEDGE_BASE", kbId,
                "Cut over sparse migration " + migration.getId());
        verify(retrievalProfileService).activate(kbId, profile.getId());
    }

    @Test
    void cutoverRejectsIncreasedFallbackRate() {
        UUID kbId = UUID.randomUUID();
        RetrievalProfile profile = profile(kbId);
        SparseMigration migration = migration(kbId, profile.getId());
        migration.setCandidateFallbackRate(0.11);
        when(repository.findByIdAndKbId(migration.getId(), kbId)).thenReturn(Optional.of(migration));
        when(profileRepository.findByIdAndKbId(profile.getId(), kbId)).thenReturn(Optional.of(profile));
        when(runRepository.findByKbIdAndStatusAndGateStatus(
                kbId, RagEvalRunStatus.COMPLETED, RagQualityGateStatus.PASS)).thenReturn(List.of(
                RagEvalRun.builder().profileSnapshot(profile.snapshot()).build()));

        assertThatThrownBy(() -> service().cutover(kbId, migration.getId()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("fallback rate increased");
    }

    private SparseMigrationService service() {
        return new SparseMigrationService(repository, profileRepository, runRepository, chunkRepository,
                knowledgeBaseService, auditLogService, retrievalProfileService, webClientBuilder);
    }

    private SparseMigrationService service(WebClient.Builder client) {
        SparseMigrationService service = new SparseMigrationService(repository, profileRepository, runRepository,
                chunkRepository, knowledgeBaseService, auditLogService, retrievalProfileService, client);
        ReflectionTestUtils.setField(service, "workerBaseUrl", "http://worker");
        return service;
    }

    private RetrievalProfile profile(UUID kbId) {
        return RetrievalProfile.builder().id(UUID.randomUUID()).kbId(kbId).name("candidate").version(3)
                .vectorCandidateCount(20).sparseCandidateCount(20).rrfConstant(60)
                .rerankEnabled(false).rerankCandidateLimit(10).finalTopK(5).build();
    }

    private SparseMigration migration(UUID kbId, UUID profileId) {
        return SparseMigration.builder().id(UUID.randomUUID()).kbId(kbId).profileId(profileId)
                .state(SparseMigrationState.SHADOW_VALIDATING).sourceChunkCount(10L).indexedChunkCount(10L)
                .expectedDimension(1536).actualDimension(1536).baselineP95Ms(100.0).candidateP95Ms(125.0)
                .baselineFallbackRate(0.1).candidateFallbackRate(0.1).build();
    }
}
