package com.dupi.rag.dto;

import java.util.List;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
public class PermissionMetadataResponse {
    private String code;
    private String name;
    private String description;
    private List<String> allows;
    private List<String> denies;
}
