package com.dupi.rag.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class BatchDocumentUploadResult {
    private String fileName;
    private boolean success;
    private String errorMessage;
    private DocumentResponse document;
}
