package com.dupi.rag.service;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultRecoveryRestoreWriterTest {
    @Test
    void remapIsStableWithinJobAndDistinctAcrossJobs() {
        UUID source = UUID.randomUUID();
        UUID firstJob = UUID.randomUUID();
        UUID secondJob = UUID.randomUUID();

        UUID first = DefaultRecoveryRestoreWriter.remap(firstJob, source);

        assertThat(DefaultRecoveryRestoreWriter.remap(firstJob, source)).isEqualTo(first);
        assertThat(DefaultRecoveryRestoreWriter.remap(secondJob, source)).isNotEqualTo(first);
        assertThat(first).isNotEqualTo(source);
    }
}
