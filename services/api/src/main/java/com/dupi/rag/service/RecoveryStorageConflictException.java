package com.dupi.rag.service;

/** A proven permanent mismatch or missing invariant at a deterministic object key. */
public class RecoveryStorageConflictException extends IllegalStateException {
    public RecoveryStorageConflictException(String message) { super(message); }
    public RecoveryStorageConflictException(String message, Throwable cause) { super(message, cause); }
}
