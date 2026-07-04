package com.dupi.rag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RetrieveRequest {

    @NotBlank
    @Size(max = 2000)
    private String query;

    @Min(1)
    @Max(50)
    private Integer topK;

    private Boolean useRerank = false;
}
