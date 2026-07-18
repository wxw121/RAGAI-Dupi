package com.dupi.rag.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class UploadQuotaResponse {
    private String tenantId;
    private String userId;
    private long retainedBytesUsed;
    private long retainedBytesLimit;
    private long retainedDocumentsUsed;
    private long retainedDocumentsLimit;
    private long windowBytesUsed;
    private long windowBytesLimit;
    private long windowSeconds;
    private Instant retryAfter;
}
