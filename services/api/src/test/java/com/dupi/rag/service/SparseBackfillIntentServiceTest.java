package com.dupi.rag.service;

import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.domain.entity.RetrievalProfile;
import com.dupi.rag.domain.entity.SparseMigration;
import com.dupi.rag.domain.enums.KnowledgeBaseLifecycleStatus;
import com.dupi.rag.domain.enums.SparseMigrationState;
import com.dupi.rag.exception.OperationConflictException;
import com.dupi.rag.repository.ChunkRepository;
import com.dupi.rag.repository.KnowledgeBaseRepository;
import com.dupi.rag.repository.RetrievalProfileRepository;
import com.dupi.rag.repository.SparseMigrationRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class SparseBackfillIntentServiceTest {

    @Test
    void deletionFirstRejectsBackfillBeforeMigrationBecomesInFlight() {
        var fixture = fixture(KnowledgeBaseLifecycleStatus.DELETING);

        assertThatThrownBy(() -> fixture.service.begin("tenant-a", fixture.kbId, fixture.migration.getId()))
                .isInstanceOf(OperationConflictException.class);

        verify(fixture.migrations, never()).saveAndFlush(any());
        verifyNoInteractions(fixture.profiles, fixture.chunks);
    }

    @Test
    void backfillFirstPersistsBackfillingIntentBeforeWorkerIoCanBegin() {
        var fixture = fixture(KnowledgeBaseLifecycleStatus.READY);
        when(fixture.migrations.findByIdAndKbId(fixture.migration.getId(), fixture.kbId))
                .thenReturn(Optional.of(fixture.migration));
        RetrievalProfile profile = RetrievalProfile.builder().id(fixture.migration.getProfileId())
                .kbId(fixture.kbId).version(7).sparseIndexParams(java.util.Map.of()).build();
        when(fixture.profiles.findByIdAndKbId(profile.getId(), fixture.kbId)).thenReturn(Optional.of(profile));
        when(fixture.chunks.countByKbId(fixture.kbId)).thenReturn(12L);
        when(fixture.migrations.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

        SparseBackfillIntent intent = fixture.service.begin("tenant-a", fixture.kbId, fixture.migration.getId());

        assertThat(intent.migration().getState()).isEqualTo(SparseMigrationState.BACKFILLING);
        assertThat(intent.sourceChunkCount()).isEqualTo(12L);
        assertThat(intent.profile().getVersion()).isEqualTo(7);
        verify(fixture.migrations).saveAndFlush(fixture.migration);
    }

    @Test
    void lateFailureCannotDowngradeAConcurrentlyCompletedBackfill() {
        var fixture = fixture(KnowledgeBaseLifecycleStatus.READY);
        fixture.migration.setState(SparseMigrationState.DUAL_WRITING);
        when(fixture.migrations.findByIdAndKbId(fixture.migration.getId(), fixture.kbId))
                .thenReturn(Optional.of(fixture.migration));

        assertThatThrownBy(() -> fixture.service.fail(
                fixture.kbId, fixture.migration.getId(), "late worker failure"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DUAL_WRITING");

        assertThat(fixture.migration.getState()).isEqualTo(SparseMigrationState.DUAL_WRITING);
        verify(fixture.migrations, never()).saveAndFlush(any());
    }

    private Fixture fixture(KnowledgeBaseLifecycleStatus lifecycle) {
        KnowledgeBaseRepository knowledgeBases = mock(KnowledgeBaseRepository.class);
        SparseMigrationRepository migrations = mock(SparseMigrationRepository.class);
        RetrievalProfileRepository profiles = mock(RetrievalProfileRepository.class);
        ChunkRepository chunks = mock(ChunkRepository.class);
        UUID kbId = UUID.randomUUID();
        KnowledgeBase kb = KnowledgeBase.builder().id(kbId).tenantId("tenant-a")
                .lifecycleStatus(lifecycle).embeddingDimension(1536).build();
        SparseMigration migration = SparseMigration.builder().id(UUID.randomUUID()).kbId(kbId)
                .profileId(UUID.randomUUID()).state(SparseMigrationState.PREPARING).build();
        when(knowledgeBases.findByIdAndTenantIdForUpdateAnyStatus(kbId, "tenant-a"))
                .thenReturn(Optional.of(kb));
        return new Fixture(kbId, migration, migrations, profiles, chunks,
                new SparseBackfillIntentService(knowledgeBases, migrations, profiles, chunks));
    }

    private record Fixture(UUID kbId, SparseMigration migration, SparseMigrationRepository migrations,
                           RetrievalProfileRepository profiles, ChunkRepository chunks,
                           SparseBackfillIntentService service) { }
}
