package com.dupi.rag.service;

import com.dupi.rag.config.RecoveryProperties;
import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.domain.entity.RecoveryArchive;
import com.dupi.rag.domain.enums.RecoveryArchiveStatus;
import com.dupi.rag.exception.KnowledgeBaseMaintenanceException;
import com.dupi.rag.repository.KnowledgeBaseRepository;
import com.dupi.rag.repository.RecoveryArchiveRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KnowledgeBaseMaintenanceServiceTest {
    @Mock KnowledgeBaseRepository knowledgeBases;
    @Mock RecoveryArchiveRepository archives;
    @Mock RecoveryActivityProbe activityProbe;

    private RecoveryProperties properties;
    private KnowledgeBaseMaintenanceService service;

    @BeforeEach
    void setUp() {
        properties = new RecoveryProperties();
        properties.setQuiescenceTimeoutSeconds(0);
        service = new KnowledgeBaseMaintenanceService(
                knowledgeBases, archives, List.of(activityProbe), properties);
    }

    @Test
    void acquireMovesOwnerToCapturingWhenWorkIsQuiescent() {
        UUID kbId = UUID.randomUUID();
        RecoveryArchive archive = archive(kbId, RecoveryArchiveStatus.PREPARING);
        when(knowledgeBases.findSystemByIdForUpdate(kbId)).thenReturn(Optional.of(
                KnowledgeBase.builder().id(kbId).tenantId("default").name("kb").build()));
        when(archives.findById(archive.getId())).thenReturn(Optional.of(archive));
        when(archives.existsActiveBySourceKnowledgeBaseIdExcluding(kbId, archive.getId())).thenReturn(false);
        when(activityProbe.hasActiveWork(kbId)).thenReturn(false);

        service.acquire(kbId, archive.getId());

        assertThat(archive.getStatus()).isEqualTo(RecoveryArchiveStatus.CAPTURING);
        verify(archives).save(archive);
    }

    @Test
    void acquireFailsClosedWhenWorkCannotBecomeQuiescent() {
        UUID kbId = UUID.randomUUID();
        RecoveryArchive archive = archive(kbId, RecoveryArchiveStatus.PREPARING);
        when(knowledgeBases.findSystemByIdForUpdate(kbId)).thenReturn(Optional.of(
                KnowledgeBase.builder().id(kbId).tenantId("default").name("kb").build()));
        when(archives.findById(archive.getId())).thenReturn(Optional.of(archive));
        when(archives.existsActiveBySourceKnowledgeBaseIdExcluding(kbId, archive.getId())).thenReturn(false);
        when(activityProbe.hasActiveWork(kbId)).thenReturn(true);

        assertThatThrownBy(() -> service.acquire(kbId, archive.getId()))
                .isInstanceOf(KnowledgeBaseMaintenanceException.class)
                .hasMessageContaining("did not become quiescent");
        assertThat(archive.getStatus()).isEqualTo(RecoveryArchiveStatus.PREPARING);
    }

    @Test
    void mutationGuardRejectsKnowledgeBaseWithActiveArchive() {
        UUID kbId = UUID.randomUUID();
        when(archives.existsBySourceKnowledgeBaseIdAndStatusIn(eq(kbId), anyCollection())).thenReturn(true);

        assertThatThrownBy(() -> service.assertMutationAllowed(kbId))
                .isInstanceOf(KnowledgeBaseMaintenanceException.class)
                .hasMessageContaining("recovery archive");
    }

    @Test
    void releaseMovesArchiveToTerminalState() {
        UUID kbId = UUID.randomUUID();
        RecoveryArchive archive = archive(kbId, RecoveryArchiveStatus.CAPTURING);
        when(archives.findById(archive.getId())).thenReturn(Optional.of(archive));

        service.release(archive.getId(), RecoveryArchiveStatus.FAILED);

        assertThat(archive.getStatus()).isEqualTo(RecoveryArchiveStatus.FAILED);
        verify(archives).save(archive);
    }

    @Test
    void acquireRejectsMissingMismatchedAndContendedArchives() {
        UUID kbId = UUID.randomUUID();
        UUID archiveId = UUID.randomUUID();
        when(knowledgeBases.findSystemByIdForUpdate(kbId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.acquire(kbId, archiveId))
                .hasMessageContaining("Knowledge base not found");

        when(knowledgeBases.findSystemByIdForUpdate(kbId)).thenReturn(Optional.of(
                KnowledgeBase.builder().id(kbId).tenantId("default").name("kb").build()));
        when(archives.findById(archiveId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.acquire(kbId, archiveId))
                .hasMessageContaining("Recovery archive not found");

        RecoveryArchive archive = archive(UUID.randomUUID(), RecoveryArchiveStatus.PREPARING);
        when(archives.findById(archiveId)).thenReturn(Optional.of(archive));
        assertThatThrownBy(() -> service.acquire(kbId, archiveId))
                .isInstanceOf(KnowledgeBaseMaintenanceException.class)
                .hasMessageContaining("cannot acquire");

        archive = archive(kbId, RecoveryArchiveStatus.PREPARING);
        when(archives.findById(archiveId)).thenReturn(Optional.of(archive));
        when(archives.existsActiveBySourceKnowledgeBaseIdExcluding(kbId, archiveId)).thenReturn(true);
        assertThatThrownBy(() -> service.acquire(kbId, archiveId))
                .isInstanceOf(KnowledgeBaseMaintenanceException.class)
                .hasMessageContaining("Another recovery archive");
    }

    @Test
    void mutationGuardAllowsQuiescentKnowledgeBaseAndReleaseRejectsNonTerminalState() {
        UUID kbId = UUID.randomUUID();
        service.assertMutationAllowed(kbId);

        assertThatThrownBy(() -> service.release(UUID.randomUUID(), RecoveryArchiveStatus.VERIFYING))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("terminal state");
    }

    private RecoveryArchive archive(UUID kbId, RecoveryArchiveStatus status) {
        return RecoveryArchive.builder()
                .id(UUID.randomUUID())
                .tenantId("default")
                .sourceKnowledgeBaseId(kbId)
                .status(status)
                .bucket("dupi-recovery")
                .objectPrefix("archives/default/a/")
                .createdBy("admin")
                .build();
    }
}
