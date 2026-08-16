package com.dupi.rag.service;

import com.dupi.rag.domain.enums.OperationStepStatus;
import com.dupi.rag.dto.OperationJobResponse;

/** The immutable result of atomically creating or reloading a Recovery import intake. */
public record RecoveryImportIntake(
        OperationJobResponse job,
        OperationStepStatus stageStatus,
        boolean published,
        StoredRecoveryObject stageObject,
        boolean cleanupPending
) {
    public RecoveryImportIntake(OperationJobResponse job, OperationStepStatus stageStatus, boolean published) {
        this(job, stageStatus, published, null, false);
    }
}
