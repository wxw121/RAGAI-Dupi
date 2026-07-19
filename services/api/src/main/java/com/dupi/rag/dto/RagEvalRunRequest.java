package com.dupi.rag.dto;

import com.dupi.rag.domain.enums.RetrievalProfile;

import java.util.List;
import lombok.Data;

@Data
public class RagEvalRunRequest {
    private Boolean useRerank = false;

    private List<RetrievalProfile> profiles = List.of(RetrievalProfile.CLASSIC);
}
