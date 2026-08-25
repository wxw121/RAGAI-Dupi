package com.dupi.rag.service;

import com.dupi.rag.domain.entity.OperationJob;
import com.dupi.rag.domain.enums.OperationType;
import com.dupi.rag.repository.OperationJobRepository;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OperationJobWriteServiceTest {
    @Test
    void successfulInsertAuditsTheSubmissionInTheInsertTransaction() {
        OperationJobRepository jobs = mock(OperationJobRepository.class);
        AuditLogService audit = mock(AuditLogService.class);
        UUID jobId = UUID.randomUUID();
        when(jobs.saveAndFlush(any())).thenAnswer(invocation -> {
            OperationJob job = invocation.getArgument(0);
            job.setId(jobId);
            return job;
        });

        new OperationJobWriteService(jobs, audit).insertJob(
                "tenant-a", OperationType.RECOVERY_ARCHIVE_IMPORT, "KNOWLEDGE_BASE",
                UUID.randomUUID(), "request-1", Map.of(), "operator");

        verify(audit).recordOperationInCurrentTransaction(
                "tenant-a", AuditLogService.OPERATION_SUBMIT, jobId, "Operation submitted");
    }
}
