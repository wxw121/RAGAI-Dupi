package com.dupi.rag.dto;

import com.dupi.rag.domain.enums.RetrievalProfile;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UpdateKnowledgeBaseRetrievalProfileRequest {

    @NotNull
    private RetrievalProfile retrievalProfile;
}
