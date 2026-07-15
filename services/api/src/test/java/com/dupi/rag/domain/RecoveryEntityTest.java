package com.dupi.rag.domain;

import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.domain.entity.RecoveryArchiveItem;
import com.dupi.rag.domain.entity.RecoveryRestoreJob;
import com.dupi.rag.domain.enums.KnowledgeBaseLifecycleStatus;
import com.dupi.rag.domain.enums.RecoveryArchiveStatus;
import com.dupi.rag.domain.enums.RecoveryItemStatus;
import com.dupi.rag.domain.enums.RecoveryRestoreStatus;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RecoveryEntityTest {

    @Test
    void knowledgeBaseDefaultsToReady() {
        KnowledgeBase knowledgeBase = KnowledgeBase.builder().name("restored").build();

        assertThat(knowledgeBase.getLifecycleStatus()).isEqualTo(KnowledgeBaseLifecycleStatus.READY);
    }

    @Test
    void recoveryStateMachinesExposeRequiredStates() {
        assertThat(RecoveryArchiveStatus.values()).containsExactly(
                RecoveryArchiveStatus.PREPARING,
                RecoveryArchiveStatus.CAPTURING,
                RecoveryArchiveStatus.VERIFYING,
                RecoveryArchiveStatus.COMPLETED,
                RecoveryArchiveStatus.FAILED
        );
        assertThat(RecoveryRestoreStatus.values()).containsExactly(
                RecoveryRestoreStatus.VALIDATING,
                RecoveryRestoreStatus.RESTORING_OBJECTS,
                RecoveryRestoreStatus.RESTORING_RECORDS,
                RecoveryRestoreStatus.RESTORING_VECTORS,
                RecoveryRestoreStatus.VERIFYING,
                RecoveryRestoreStatus.COMPLETED,
                RecoveryRestoreStatus.FAILED
        );
    }

    @Test
    void archiveItemCarriesStableKeyAndProgress() {
        UUID archiveId = UUID.randomUUID();
        RecoveryArchiveItem item = RecoveryArchiveItem.builder()
                .archiveId(archiveId)
                .itemKey("object:doc-1")
                .status(RecoveryItemStatus.PENDING)
                .build();

        assertThat(item.getArchiveId()).isEqualTo(archiveId);
        assertThat(item.getItemKey()).isEqualTo("object:doc-1");
        assertThat(item.getStatus()).isEqualTo(RecoveryItemStatus.PENDING);
    }

    @Test
    void restoreJobCarriesOneStableTarget() {
        UUID archiveId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        RecoveryRestoreJob job = RecoveryRestoreJob.builder()
                .archiveId(archiveId)
                .targetKnowledgeBaseId(targetId)
                .status(RecoveryRestoreStatus.VALIDATING)
                .build();

        assertThat(job.getArchiveId()).isEqualTo(archiveId);
        assertThat(job.getTargetKnowledgeBaseId()).isEqualTo(targetId);
    }
}
