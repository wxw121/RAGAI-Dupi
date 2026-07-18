package com.dupi.rag.exception;

public class UploadIdempotencyConflictException extends RuntimeException {
    public UploadIdempotencyConflictException(String message) {
        super(message);
    }
}
