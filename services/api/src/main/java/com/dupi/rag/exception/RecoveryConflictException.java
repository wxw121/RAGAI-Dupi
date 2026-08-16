package com.dupi.rag.exception;

public class RecoveryConflictException extends RuntimeException {
    private final String suggestion;

    public RecoveryConflictException(String message, String suggestion) {
        super(message);
        this.suggestion = suggestion;
    }

    public String getSuggestion() {
        return suggestion;
    }
}
