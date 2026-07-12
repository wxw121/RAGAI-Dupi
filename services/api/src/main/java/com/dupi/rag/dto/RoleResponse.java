package com.dupi.rag.dto;

import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
public class RoleResponse {
    private UUID id;
    private String code;
    private String name;
    private String description;
    private List<String> permissions;
    private boolean systemBuiltin;
    private boolean disabled;
    private long userCount;
}
