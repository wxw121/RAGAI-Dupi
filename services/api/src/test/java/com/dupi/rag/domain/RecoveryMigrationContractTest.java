package com.dupi.rag.domain;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class RecoveryMigrationContractTest {
    @Test
    void deletingArchiveCascadesCompletedRestoreEvidence() throws Exception {
        try (var input = getClass().getResourceAsStream("/db/migration/V15__recovery_archive_retention.sql")) {
            assertThat(input).isNotNull();
            String migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(migration).contains("DROP CONSTRAINT recovery_restore_jobs_archive_id_fkey")
                    .contains("FOREIGN KEY (archive_id) REFERENCES recovery_archives(id) ON DELETE CASCADE");
        }
        try (var input = getClass().getResourceAsStream("/db/migration/V16__recovery_archive_item_retention.sql")) {
            assertThat(input).isNotNull();
            String migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(migration).contains("DROP CONSTRAINT recovery_restore_items_archive_item_id_fkey")
                    .contains("FOREIGN KEY (archive_item_id) REFERENCES recovery_archive_items(id) ON DELETE CASCADE");
        }
    }
}
