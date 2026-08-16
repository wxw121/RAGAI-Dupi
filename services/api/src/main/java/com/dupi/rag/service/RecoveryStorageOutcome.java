package com.dupi.rag.service;

/** A deterministic comparison between planned bytes and the object currently stored at their key. */
public enum RecoveryStorageOutcome {
    ABSENT,
    MATCHING,
    CONFLICT
}
