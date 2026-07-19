package com.dupi.rag.exception;

import lombok.Getter;

@Getter
public class RetrievalProfileConflictException extends RuntimeException {
    private final String reason;

    public RetrievalProfileConflictException(String reason, String message) {
        super(message);
        this.reason = reason;
    }

    public static RetrievalProfileConflictException indexNotReady() {
        return new RetrievalProfileConflictException(
                "index_not_ready",
                "Profile index is not ready for non-classic retrieval"
        );
    }
}
