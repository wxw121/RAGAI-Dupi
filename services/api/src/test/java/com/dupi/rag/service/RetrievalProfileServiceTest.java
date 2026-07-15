package com.dupi.rag.service;

import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.domain.entity.RagEvalRun;
import com.dupi.rag.domain.entity.RetrievalProfile;
import com.dupi.rag.domain.enums.RagEvalRunStatus;
import com.dupi.rag.domain.enums.RagQualityGateStatus;
import com.dupi.rag.repository.RagEvalRunRepository;
import com.dupi.rag.repository.RetrievalProfileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class RetrievalProfileServiceTest {
    @Mock RetrievalProfileRepository profileRepository;
    @Mock RagEvalRunRepository runRepository;
    @Mock KnowledgeBaseService knowledgeBaseService;
    @Mock AuditLogService auditLogService;

    @Test
    void createListAndResolveActiveProfile() {
        UUID kbId = UUID.randomUUID();
        RetrievalProfile previous = profile(kbId);
        KnowledgeBase kb = KnowledgeBase.builder().id(kbId).activeRetrievalProfileId(previous.getId()).build();
        when(knowledgeBaseService.findOrThrow(kbId)).thenReturn(kb);
        when(profileRepository.findByKbIdOrderByVersionDesc(kbId)).thenReturn(List.of(previous));
        when(profileRepository.findByIdAndKbId(previous.getId(), kbId)).thenReturn(Optional.of(previous));
        when(profileRepository.findTopByKbIdOrderByVersionDesc(kbId)).thenReturn(Optional.of(previous));
        when(profileRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        com.dupi.rag.dto.RetrievalProfileRequest request = request();
        request.setName(" next ");
        request.setSparseIndexParams(Map.of("bm25", Map.of("k1", 1.5)));
        request.setSparseSearchParams(Map.of("drop", List.of(0.1, 0.2)));

        var listed = service().list(kbId);
        var created = service().create(kbId, request);
        var active = service().resolveActive(kb);

        assertThat(listed).singleElement().satisfies(item -> assertThat(item.isActive()).isTrue());
        assertThat(created.getName()).isEqualTo("next");
        assertThat(created.getVersion()).isEqualTo(3);
        assertThat(created.getSparseIndexParams()).containsKey("bm25");
        assertThat(active).isSameAs(previous);
    }

    @Test
    void firstProfileUsesVersionOneAndNullParameterMaps() {
        UUID kbId = UUID.randomUUID();
        com.dupi.rag.dto.RetrievalProfileRequest request = request();
        request.setSparseIndexParams(null);
        request.setSparseSearchParams(null);
        when(profileRepository.findTopByKbIdOrderByVersionDesc(kbId)).thenReturn(Optional.empty());
        when(profileRepository.save(any())).thenReturn(null);

        var created = service().create(kbId, request);

        assertThat(created.getVersion()).isEqualTo(1);
        assertThat(created.getSparseIndexParams()).isEmpty();
        assertThat(created.getSparseSearchParams()).isEmpty();
        assertThat(service().resolveActive(KnowledgeBase.builder().id(kbId).build())).isNull();
    }

    @Test
    void missingProfileAndInvalidRollbackAreRejected() {
        UUID kbId = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        when(profileRepository.findByIdAndKbId(missing, kbId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().find(kbId, missing))
                .isInstanceOf(com.dupi.rag.exception.ResourceNotFoundException.class);

        RetrievalProfile target = profile(kbId);
        KnowledgeBase kb = KnowledgeBase.builder().id(kbId).build();
        when(profileRepository.findByIdAndKbId(target.getId(), kbId)).thenReturn(Optional.of(target));
        when(runRepository.findByKbIdAndStatusAndGateStatus(kbId, RagEvalRunStatus.COMPLETED,
                RagQualityGateStatus.PASS)).thenReturn(List.of(RagEvalRun.builder()
                .profileSnapshot(target.snapshot()).build()));
        when(knowledgeBaseService.findForUpdateOrThrow(kbId)).thenReturn(kb);
        assertThatThrownBy(() -> service().rollback(kbId, target.getId()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("older");
    }

    @Test
    void activateRejectsProfileWithoutMatchingPassingQualityGate() {
        UUID kbId = UUID.randomUUID();
        RetrievalProfile profile = profile(kbId);
        when(profileRepository.findByIdAndKbId(profile.getId(), kbId)).thenReturn(Optional.of(profile));
        when(runRepository.findByKbIdAndStatusAndGateStatus(
                kbId, RagEvalRunStatus.COMPLETED, RagQualityGateStatus.PASS)).thenReturn(List.of());

        assertThatThrownBy(() -> service().activate(kbId, profile.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("passing quality gate");
    }

    @Test
    void activateUsesExactPassingSnapshotAndUpdatesKnowledgeBase() {
        UUID kbId = UUID.randomUUID();
        RetrievalProfile profile = profile(kbId);
        KnowledgeBase kb = KnowledgeBase.builder().id(kbId).name("KB").build();
        RagEvalRun run = RagEvalRun.builder()
                .kbId(kbId)
                .status(RagEvalRunStatus.COMPLETED)
                .gateStatus(RagQualityGateStatus.PASS)
                .profileSnapshot(profile.snapshot())
                .build();
        when(profileRepository.findByIdAndKbId(profile.getId(), kbId)).thenReturn(Optional.of(profile));
        when(runRepository.findByKbIdAndStatusAndGateStatus(
                kbId, RagEvalRunStatus.COMPLETED, RagQualityGateStatus.PASS)).thenReturn(List.of(run));
        when(knowledgeBaseService.findForUpdateOrThrow(kbId)).thenReturn(kb);

        var response = service().activate(kbId, profile.getId());

        assertThat(kb.getActiveRetrievalProfileId()).isEqualTo(profile.getId());
        assertThat(response.getVersion()).isEqualTo(2);
        verify(auditLogService).recordSuccessInCurrentTransaction("RETRIEVAL_PROFILE_ACTIVATE", "KNOWLEDGE_BASE", kbId,
                "Activated retrieval profile " + profile.getId());
    }

    @Test
    void rollbackReactivatesOlderPassingProfileAndAuditsTransition() {
        UUID kbId = UUID.randomUUID();
        RetrievalProfile previous = profile(kbId);
        RetrievalProfile target = RetrievalProfile.builder()
                .id(UUID.randomUUID())
                .kbId(kbId)
                .name("stable")
                .version(1)
                .vectorCandidateCount(20)
                .sparseCandidateCount(20)
                .rrfConstant(60)
                .rerankEnabled(false)
                .rerankCandidateLimit(10)
                .finalTopK(5)
                .build();
        KnowledgeBase kb = KnowledgeBase.builder()
                .id(kbId)
                .name("KB")
                .activeRetrievalProfileId(previous.getId())
                .build();
        RagEvalRun run = RagEvalRun.builder()
                .kbId(kbId)
                .status(RagEvalRunStatus.COMPLETED)
                .gateStatus(RagQualityGateStatus.PASS)
                .profileSnapshot(target.snapshot())
                .build();
        when(profileRepository.findByIdAndKbId(target.getId(), kbId)).thenReturn(Optional.of(target));
        when(profileRepository.findByIdAndKbId(previous.getId(), kbId)).thenReturn(Optional.of(previous));
        when(runRepository.findByKbIdAndStatusAndGateStatus(
                kbId, RagEvalRunStatus.COMPLETED, RagQualityGateStatus.PASS)).thenReturn(List.of(run));
        when(knowledgeBaseService.findForUpdateOrThrow(kbId)).thenReturn(kb);

        var response = service().rollback(kbId, target.getId());

        assertThat(response.getId()).isEqualTo(target.getId());
        assertThat(kb.getActiveRetrievalProfileId()).isEqualTo(target.getId());
        verify(auditLogService).recordSuccessInCurrentTransaction(
                "RETRIEVAL_PROFILE_ROLLBACK", "KNOWLEDGE_BASE", kbId,
                "Rolled back retrieval profile from " + previous.getId() + " to " + target.getId());
    }

    @Test
    void activateRejectsDowngradeThatWouldBypassRollbackAudit() {
        UUID kbId = UUID.randomUUID();
        RetrievalProfile previous = profile(kbId);
        RetrievalProfile target = RetrievalProfile.builder()
                .id(UUID.randomUUID()).kbId(kbId).name("old").version(1)
                .vectorCandidateCount(20).sparseCandidateCount(20).rrfConstant(60)
                .rerankEnabled(false).rerankCandidateLimit(10).finalTopK(5).build();
        KnowledgeBase kb = KnowledgeBase.builder().id(kbId).name("KB")
                .activeRetrievalProfileId(previous.getId()).build();
        RagEvalRun run = RagEvalRun.builder().kbId(kbId).status(RagEvalRunStatus.COMPLETED)
                .gateStatus(RagQualityGateStatus.PASS).profileSnapshot(target.snapshot()).build();
        when(profileRepository.findByIdAndKbId(target.getId(), kbId)).thenReturn(Optional.of(target));
        when(profileRepository.findByIdAndKbId(previous.getId(), kbId)).thenReturn(Optional.of(previous));
        when(runRepository.findByKbIdAndStatusAndGateStatus(
                kbId, RagEvalRunStatus.COMPLETED, RagQualityGateStatus.PASS)).thenReturn(List.of(run));
        when(knowledgeBaseService.findForUpdateOrThrow(kbId)).thenReturn(kb);

        assertThatThrownBy(() -> service().activate(kbId, target.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rollback endpoint");
    }

    @Test
    void createRejectsSensitiveSparseParameters() {
        UUID kbId = UUID.randomUUID();
        com.dupi.rag.dto.RetrievalProfileRequest request = new com.dupi.rag.dto.RetrievalProfileRequest();
        request.setName("unsafe");
        request.setVectorCandidateCount(20);
        request.setSparseCandidateCount(20);
        request.setRrfConstant(60);
        request.setSparseSearchParams(Map.of("provider_url", "https://secret"));
        request.setRerankEnabled(false);
        request.setRerankCandidateLimit(10);
        request.setFinalTopK(5);

        assertThatThrownBy(() -> service().create(kbId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sensitive");
    }

    private RetrievalProfileService service() {
        return new RetrievalProfileService(profileRepository, runRepository, knowledgeBaseService, auditLogService);
    }

    private RetrievalProfile profile(UUID kbId) {
        return RetrievalProfile.builder()
                .id(UUID.randomUUID())
                .kbId(kbId)
                .name("balanced")
                .version(2)
                .vectorCandidateCount(30)
                .sparseCandidateCount(30)
                .rrfConstant(60)
                .rerankEnabled(true)
                .rerankCandidateLimit(20)
                .finalTopK(5)
                .build();
    }

    private com.dupi.rag.dto.RetrievalProfileRequest request() {
        com.dupi.rag.dto.RetrievalProfileRequest request = new com.dupi.rag.dto.RetrievalProfileRequest();
        request.setName("candidate");
        request.setVectorCandidateCount(20);
        request.setSparseCandidateCount(20);
        request.setRrfConstant(60);
        request.setRerankEnabled(false);
        request.setRerankCandidateLimit(10);
        request.setFinalTopK(5);
        return request;
    }
}
