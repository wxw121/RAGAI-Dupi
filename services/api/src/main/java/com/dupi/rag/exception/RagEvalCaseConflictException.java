package com.dupi.rag.exception;

import java.util.List;

public class RagEvalCaseConflictException extends RuntimeException {

    private final String errorCode;

    private RagEvalCaseConflictException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public static RagEvalCaseConflictException invalidSources(List<String> details) {
        String suffix = details == null || details.isEmpty() ? "" : ": " + String.join("; ", details);
        return new RagEvalCaseConflictException(
                "rag_eval_sources_invalid",
                "RAG evaluation cases reference missing or incomplete documents" + suffix);
    }

    public static RagEvalCaseConflictException stalePreview() {
        return new RagEvalCaseConflictException(
                "rag_eval_generation_preview_stale",
                "Knowledge base documents or evaluation cases changed after the preview was generated");
    }

    public String getErrorCode() {
        return errorCode;
    }
}
