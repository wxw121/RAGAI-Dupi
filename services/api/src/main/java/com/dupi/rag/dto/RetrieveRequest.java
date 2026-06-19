package com.dupi.rag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RetrieveRequest {

    @NotBlank
    @Size(max = 2000)
    private String query;

    private Integer topK;

    private Boolean useRerank = false;
}
