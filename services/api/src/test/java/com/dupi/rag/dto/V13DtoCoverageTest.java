package com.dupi.rag.dto;

import com.dupi.rag.domain.entity.SparseMigration;
import com.dupi.rag.domain.enums.RagEvalComparisonStatus;
import com.dupi.rag.domain.enums.RetrievalMode;
import com.dupi.rag.domain.enums.SparseMigrationState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class V13DtoCoverageTest {
    @Test
    void qualityPolicyDtoExposesEveryThresholdAndBaselineField() {
        UUID id = UUID.randomUUID();
        RagQualityPolicyRequest request = new RagQualityPolicyRequest();
        request.setMinimumPassRate(90); request.setMaximumPassRateDrop(3);
        request.setMaximumNewFailures(1); request.setBlockWhenUnbaselined(true);
        assertThat(request.getMinimumPassRate()).isEqualTo(90);
        assertThat(request.getMaximumPassRateDrop()).isEqualTo(3);
        assertThat(request.getMaximumNewFailures()).isEqualTo(1);
        assertThat(request.getBlockWhenUnbaselined()).isTrue();

        RagQualityPolicyResponse response = new RagQualityPolicyResponse();
        response.setId(id); response.setKbId(id); response.setMinimumPassRate(90);
        response.setMaximumPassRateDrop(3); response.setMaximumNewFailures(1);
        response.setBlockWhenUnbaselined(true); response.setBaselineRunId(id);
        response.setCreatedAt(Instant.EPOCH); response.setUpdatedAt(Instant.EPOCH);
        assertThat(response.getId()).isEqualTo(id); assertThat(response.getKbId()).isEqualTo(id);
        assertThat(response.getMinimumPassRate()).isEqualTo(90);
        assertThat(response.getMaximumPassRateDrop()).isEqualTo(3);
        assertThat(response.getMaximumNewFailures()).isEqualTo(1);
        assertThat(response.getBlockWhenUnbaselined()).isTrue();
        assertThat(response.getBaselineRunId()).isEqualTo(id);
        assertThat(response.getCreatedAt()).isEqualTo(Instant.EPOCH);
        assertThat(response.getUpdatedAt()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void evaluationRequestAndRankEvidenceAreMutable() {
        UUID id = UUID.randomUUID();
        RagEvalRunRequest request = new RagEvalRunRequest();
        request.setUseRerank(true);
        request.setProfileId(id);
        request.setRetrievalMode(RetrievalMode.HYBRID);
        assertThat(request.getUseRerank()).isTrue();
        assertThat(request.getProfileId()).isEqualTo(id);
        assertThat(request.getRetrievalMode()).isEqualTo(RetrievalMode.HYBRID);

        RagEvalRunResultResponse result = new RagEvalRunResultResponse();
        result.setId(id); result.setCaseId(id); result.setCaseKey("case"); result.setQuery("query");
        result.setPassed(true); result.setFailureReasons(List.of()); result.setHitCount(2);
        result.setExpectedFileName("a.md"); result.setMatchedFileName("a.md"); result.setMatchedToken("token");
        result.setRetrievalMode("hybrid_rerank"); result.setFallbackReason(null); result.setEmbeddingModel("embed");
        result.setEmbeddingDimension(8); result.setTopK(5); result.setCaseFingerprint("fingerprint");
        result.setComparisonStatus(RagEvalComparisonStatus.UNCHANGED); result.setLatencyMs(12L);
        result.setMatchedRank(1); result.setVectorRank(2); result.setSparseRank(1);
        result.setFusionRank(1); result.setRerankRank(1);
        assertThat(result).extracting(RagEvalRunResultResponse::getMatchedRank,
                RagEvalRunResultResponse::getVectorRank, RagEvalRunResultResponse::getSparseRank,
                RagEvalRunResultResponse::getFusionRank, RagEvalRunResultResponse::getRerankRank)
                .containsExactly(1, 2, 1, 1, 1);
        assertThat(result.getId()).isEqualTo(id);
        assertThat(result.getCaseId()).isEqualTo(id);
        assertThat(result.getCaseKey()).isEqualTo("case");
        assertThat(result.getQuery()).isEqualTo("query");
        assertThat(result.isPassed()).isTrue();
        assertThat(result.getFailureReasons()).isEmpty();
        assertThat(result.getHitCount()).isEqualTo(2);
        assertThat(result.getExpectedFileName()).isEqualTo("a.md");
        assertThat(result.getMatchedFileName()).isEqualTo("a.md");
        assertThat(result.getMatchedToken()).isEqualTo("token");
        assertThat(result.getRetrievalMode()).isEqualTo("hybrid_rerank");
        assertThat(result.getEmbeddingModel()).isEqualTo("embed");
        assertThat(result.getEmbeddingDimension()).isEqualTo(8);
        assertThat(result.getTopK()).isEqualTo(5);
        assertThat(result.getCaseFingerprint()).isEqualTo("fingerprint");
        assertThat(result.getComparisonStatus()).isEqualTo(RagEvalComparisonStatus.UNCHANGED);
        assertThat(result.getLatencyMs()).isEqualTo(12);
    }

    @Test
    void profileAndMigrationResponsesExposeOperationalFields() {
        UUID id = UUID.randomUUID();
        RetrievalProfileResponse profile = RetrievalProfileResponse.builder().id(id).kbId(id).name("p").version(3)
                .vectorCandidateCount(20).sparseCandidateCount(30).rrfConstant(60)
                .sparseIndexParams(Map.of("k1", 1.5)).sparseSearchParams(Map.of())
                .rerankEnabled(true).rerankCandidateLimit(10).finalTopK(5).active(true).createdAt(Instant.EPOCH).build();
        assertThat(profile.getId()).isEqualTo(id); assertThat(profile.getKbId()).isEqualTo(id);
        assertThat(profile.getName()).isEqualTo("p"); assertThat(profile.getVersion()).isEqualTo(3);
        assertThat(profile.getVectorCandidateCount()).isEqualTo(20); assertThat(profile.getSparseCandidateCount()).isEqualTo(30);
        assertThat(profile.getRrfConstant()).isEqualTo(60); assertThat(profile.getSparseIndexParams()).containsKey("k1");
        assertThat(profile.getSparseSearchParams()).isEmpty(); assertThat(profile.getRerankEnabled()).isTrue();
        assertThat(profile.getRerankCandidateLimit()).isEqualTo(10); assertThat(profile.getFinalTopK()).isEqualTo(5);
        assertThat(profile.isActive()).isTrue(); assertThat(profile.getCreatedAt()).isEqualTo(Instant.EPOCH);
        profile.setActive(false); profile.setName("updated"); profile.setVersion(4);
        assertThat(profile.isActive()).isFalse(); assertThat(profile.getName()).isEqualTo("updated");
        assertThat(profile.getVersion()).isEqualTo(4); assertThat(profile.toString()).contains("updated");

        SparseMigration migration = SparseMigration.builder().id(id).kbId(id).profileId(id)
                .state(SparseMigrationState.DUAL_WRITING).sourceChunkCount(2L).indexedChunkCount(2L)
                .expectedDimension(8).actualDimension(8).baselineP95Ms(100.0).candidateP95Ms(110.0)
                .baselineFallbackRate(0.01).candidateFallbackRate(0.0)
                .legacyBm25Enabled(true).errorMessage("none").createdAt(Instant.EPOCH).updatedAt(Instant.EPOCH).build();
        SparseMigrationResponse response = SparseMigrationResponse.from(migration);
        assertThat(response.getId()).isEqualTo(id); assertThat(response.getKbId()).isEqualTo(id);
        assertThat(response.getProfileId()).isEqualTo(id); assertThat(response.getState()).isEqualTo(SparseMigrationState.DUAL_WRITING);
        assertThat(response.getSourceChunkCount()).isEqualTo(2); assertThat(response.getIndexedChunkCount()).isEqualTo(2);
        assertThat(response.getLegacyBm25Enabled()).isTrue(); assertThat(response.getErrorMessage()).isEqualTo("none");
        assertThat(response.getExpectedDimension()).isEqualTo(8); assertThat(response.getActualDimension()).isEqualTo(8);
        assertThat(response.getBaselineP95Ms()).isEqualTo(100.0); assertThat(response.getCandidateP95Ms()).isEqualTo(110.0);
        assertThat(response.getBaselineFallbackRate()).isEqualTo(0.01); assertThat(response.getCandidateFallbackRate()).isZero();
        assertThat(response.getCreatedAt()).isEqualTo(Instant.EPOCH); assertThat(response.getUpdatedAt()).isEqualTo(Instant.EPOCH);
    }
}
