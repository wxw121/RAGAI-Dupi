package com.dupi.rag.exception;

import lombok.Getter;

@Getter
public class ChatPipelineException extends RuntimeException {
    private final String stage;
    private final String suggestion;

    public ChatPipelineException(String stage, String message, String suggestion, Throwable cause) {
        super(message, cause);
        this.stage = stage;
        this.suggestion = suggestion;
    }
}
