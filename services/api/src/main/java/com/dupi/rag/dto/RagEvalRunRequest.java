package com.dupi.rag.dto;

import com.dupi.rag.domain.enums.RetrievalProfile;
import com.dupi.rag.domain.enums.RetrievalMode;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import lombok.Data;

@Data
public class RagEvalRunRequest {
    private Boolean useRerank = false;

    private List<RetrievalProfile> profiles = List.of(RetrievalProfile.CLASSIC);
    private UUID profileId;
    private RetrievalMode retrievalMode;

    @Min(1)
    @Max(50)
    private Integer topKOverride;

    @Size(max = 128)
    private String experimentLabel;
}
