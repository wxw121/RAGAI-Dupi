package com.dupi.rag.exception;

import com.dupi.rag.dto.RagEvalGateDecisionResponse;
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

    public static RetrievalProfileConflictException gateBlocked(RagEvalGateDecisionResponse decision) {
        return new RetrievalProfileConflictException(
                decision.getReason(),
                "Retrieval profile gate blocked: " + decision.getReason()
        );
    }
}
