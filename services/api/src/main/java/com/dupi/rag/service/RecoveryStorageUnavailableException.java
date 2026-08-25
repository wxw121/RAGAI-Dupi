package com.dupi.rag.service;

/** The object store could not provide a trustworthy outcome and the operation must be retried. */
public class RecoveryStorageUnavailableException extends IllegalStateException {
    public RecoveryStorageUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
