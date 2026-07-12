package com.dupi.rag.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class BatchDocumentUploadResponse {
    private int total;
    private int succeeded;
    private int failed;
    private List<BatchDocumentUploadResult> results;
}
