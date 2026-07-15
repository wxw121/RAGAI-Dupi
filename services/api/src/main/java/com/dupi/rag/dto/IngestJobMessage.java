package com.dupi.rag.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class IngestJobMessage {
    private String jobId;
    private String kbId;
    private String docId;
    private String objectKey;
    private String fileName;
    private String mimeType;
    private Integer chunkSize;
    private Integer chunkOverlap;
    private String chunkStrategy;
    private String embeddingModel;
    private Integer embeddingDimension;
    private Integer sparseProfileVersion;
}
