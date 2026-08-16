package com.dupi.rag.domain;

import com.dupi.rag.domain.entity.OperationJob;
import com.dupi.rag.domain.entity.OperationStep;
import com.dupi.rag.domain.enums.OperationStatus;
import com.dupi.rag.domain.enums.OperationStepStatus;
import org.junit.jupiter.api.Test;
import jakarta.persistence.Column;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class DurableOperationContractTest {

    @Test
    void operationMigrationDefinesIdempotentJobsAndSteps() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V25__durable_operation_jobs.sql"));

        assertThat(sql).contains("CREATE TABLE operation_jobs")
                .contains("UNIQUE (tenant_id, operation_type, idempotency_key)")
                .contains("CREATE TABLE operation_steps")
                .contains("UNIQUE (job_id, step_key)");
    }

    @Test
    void entityDefaultsRepresentPreparedWork() {
        OperationJob job = OperationJob.builder().build();
        OperationStep step = OperationStep.builder().build();

        assertThat(job.getStatus()).isEqualTo(OperationStatus.PREPARED);
        assertThat(step.getStatus()).isEqualTo(OperationStepStatus.PENDING);
    }

    @Test
    void terminalRetrySchedulesAreNullableInMigrationAndJpaMappings() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V25__durable_operation_jobs.sql"));

        assertThat(sql.lines().map(String::trim).filter(line -> line.startsWith("next_attempt_at ")).toList())
                .hasSize(2)
                .allSatisfy(line -> assertThat(line).doesNotContain("NOT NULL"));
        assertThat(column(OperationJob.class, "nextAttemptAt").nullable()).isTrue();
        assertThat(column(OperationStep.class, "nextAttemptAt").nullable()).isTrue();
    }

    private static Column column(Class<?> entityType, String fieldName) throws NoSuchFieldException {
        Field field = entityType.getDeclaredField(fieldName);
        return field.getAnnotation(Column.class);
    }
}
