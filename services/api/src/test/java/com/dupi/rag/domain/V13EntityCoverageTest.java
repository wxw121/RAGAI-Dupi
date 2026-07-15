package com.dupi.rag.domain;

import com.dupi.rag.domain.entity.RetrievalProfile;
import com.dupi.rag.domain.entity.SparseMigration;
import com.dupi.rag.domain.enums.SparseMigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class V13EntityCoverageTest {
    @Test
    void retrievalProfileFreezesNestedParametersAndBuildsSnapshot() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("values", new ArrayList<>(List.of(1, 2)));
        RetrievalProfile profile = RetrievalProfile.builder().kbId(UUID.randomUUID()).name("p").version(1)
                .vectorCandidateCount(10).sparseCandidateCount(10).rrfConstant(60)
                .sparseIndexParams(nested).sparseSearchParams(null).rerankEnabled(true)
                .rerankCandidateLimit(5).finalTopK(3).build();
        ReflectionTestUtils.invokeMethod(profile, "onCreate");

        assertThat(profile.getId()).isNotNull();
        assertThat(profile.getCreatedAt()).isNotNull();
        assertThat(profile.snapshot()).containsEntry("version", 1).containsEntry("rerankEnabled", true);
        assertThat(profile.getSparseSearchParams()).isEmpty();
        assertThatThrownBy(() -> profile.getSparseIndexParams().put("x", 1))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void sparseMigrationLifecycleProvidesDefaultsAndVersion() {
        SparseMigration migration = SparseMigration.builder().kbId(UUID.randomUUID()).profileId(UUID.randomUUID()).build();
        ReflectionTestUtils.invokeMethod(migration, "create");
        assertThat(migration.getId()).isNotNull();
        assertThat(migration.getState()).isEqualTo(SparseMigrationState.PREPARING);
        assertThat(migration.getSourceChunkCount()).isZero();
        assertThat(migration.getIndexedChunkCount()).isZero();
        assertThat(migration.getLegacyBm25Enabled()).isFalse();
        assertThat(migration.getCreatedAt()).isNotNull();
        assertThat(migration.getUpdatedAt()).isNotNull();
        var created = migration.getUpdatedAt();
        ReflectionTestUtils.invokeMethod(migration, "update");
        assertThat(migration.getUpdatedAt()).isAfterOrEqualTo(created);
        assertThat(migration.getVersion()).isZero();
    }
}
