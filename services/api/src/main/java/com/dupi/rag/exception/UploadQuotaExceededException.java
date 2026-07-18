package com.dupi.rag.exception;

public class UploadQuotaExceededException extends RuntimeException {
    private final Long retryAfterSeconds;

    public UploadQuotaExceededException(String message) {
        this(message, null);
    }

    public UploadQuotaExceededException(String message, Long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public Long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
