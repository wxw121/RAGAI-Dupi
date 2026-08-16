package com.dupi.rag.service;

/** A content decision together with the trustworthy object version observed during inspection. */
public record RecoveryStorageInspection(
        RecoveryStorageOutcome outcome,
        StoredRecoveryObject object
) {
    static RecoveryStorageInspection absent() {
        return new RecoveryStorageInspection(RecoveryStorageOutcome.ABSENT, null);
    }

    static RecoveryStorageInspection of(RecoveryStorageOutcome outcome, StoredRecoveryObject object) {
        return new RecoveryStorageInspection(outcome, object);
    }
}
