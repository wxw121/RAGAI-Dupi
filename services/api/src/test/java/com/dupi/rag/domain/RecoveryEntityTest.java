package com.dupi.rag.domain;

import com.dupi.rag.domain.entity.KnowledgeBase;
import com.dupi.rag.domain.entity.RecoveryArchive;
import com.dupi.rag.domain.entity.RecoveryArchiveItem;
import com.dupi.rag.domain.entity.RecoveryRestoreJob;
import com.dupi.rag.domain.entity.RecoveryRestoreItem;
import com.dupi.rag.domain.enums.KnowledgeBaseLifecycleStatus;
import com.dupi.rag.domain.enums.RecoveryArchiveStatus;
import com.dupi.rag.domain.enums.RecoveryItemStatus;
import com.dupi.rag.domain.enums.RecoveryRestoreStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
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

    @Test
    void recoveryEntitiesInitializeIdsTimestampsAndDefaults() {
        RecoveryArchive archive = RecoveryArchive.builder().tenantId("default")
                .sourceKnowledgeBaseId(UUID.randomUUID()).bucket("b").objectPrefix("p")
                .createdBy("admin").build();
        RecoveryArchiveItem archiveItem = RecoveryArchiveItem.builder().archiveId(UUID.randomUUID())
                .itemKey("k").itemType("RECORD").objectKey("o").build();
        RecoveryRestoreJob job = RecoveryRestoreJob.builder().archiveId(UUID.randomUUID())
                .tenantId("default").createdBy("admin").build();
        RecoveryRestoreItem restoreItem = RecoveryRestoreItem.builder().restoreJobId(UUID.randomUUID())
                .archiveItemId(UUID.randomUUID()).build();

        ReflectionTestUtils.invokeMethod(archive, "create");
        ReflectionTestUtils.invokeMethod(archiveItem, "create");
        ReflectionTestUtils.invokeMethod(job, "create");
        ReflectionTestUtils.invokeMethod(restoreItem, "create");

        assertThat(archive.getId()).isNotNull();
        assertThat(archive.getStatus()).isEqualTo(RecoveryArchiveStatus.PREPARING);
        assertThat(archiveItem.getId()).isNotNull();
        assertThat(archiveItem.getAttemptCount()).isZero();
        assertThat(job.getId()).isNotNull();
        assertThat(restoreItem.getId()).isNotNull();
        assertThat(restoreItem.getStatus()).isEqualTo(RecoveryItemStatus.PENDING);

        Instant archiveCreated = archive.getCreatedAt();
        Instant archiveItemCreated = archiveItem.getCreatedAt();
        Instant jobCreated = job.getCreatedAt();
        Instant restoreItemCreated = restoreItem.getCreatedAt();
        ReflectionTestUtils.invokeMethod(archive, "update");
        ReflectionTestUtils.invokeMethod(archiveItem, "update");
        ReflectionTestUtils.invokeMethod(job, "update");
        ReflectionTestUtils.invokeMethod(restoreItem, "update");

        assertThat(archive.getUpdatedAt()).isAfterOrEqualTo(archiveCreated);
        assertThat(archiveItem.getUpdatedAt()).isAfterOrEqualTo(archiveItemCreated);
        assertThat(job.getUpdatedAt()).isAfterOrEqualTo(jobCreated);
        assertThat(restoreItem.getUpdatedAt()).isAfterOrEqualTo(restoreItemCreated);
    }
}
