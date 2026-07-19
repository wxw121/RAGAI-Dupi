package com.dupi.rag.dto;

import com.dupi.rag.domain.enums.RetrievalProfile;
import com.dupi.rag.domain.enums.RetrievalMode;

import java.util.List;
import java.util.UUID;
import lombok.Data;

@Data
public class RagEvalRunRequest {
    private Boolean useRerank = false;

    private List<RetrievalProfile> profiles = List.of(RetrievalProfile.CLASSIC);
    private UUID profileId;
    private RetrievalMode retrievalMode;
}
