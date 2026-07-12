package com.dupi.rag.dto;

import java.time.Instant;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
public class AuditAlertResponse {
    private String code;
    private String severity;
    private String message;
    private long count;
    private long threshold;
    private Instant windowStart;
    private Instant windowEnd;
}
