package com.dupi.rag.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class IngestDiagnosisResponse {
    private String severity;
    private String summary;
    private String nextAction;
    private boolean retryable;
    private boolean stalled;
    private long ageSeconds;
    private long lastUpdatedSeconds;
}
