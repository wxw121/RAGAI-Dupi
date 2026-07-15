package com.dupi.rag.domain.enums;

public enum SparseMigrationState {
    PREPARING, BACKFILLING, DUAL_WRITING, SHADOW_VALIDATING, CUTOVER, COMPLETED, FAILED
}
