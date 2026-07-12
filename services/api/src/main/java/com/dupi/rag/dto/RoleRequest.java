package com.dupi.rag.dto;

import java.util.List;
import lombok.Data;

@Data
public class RoleRequest {
    private String code;
    private String name;
    private String description;
    private List<String> permissions;
    private Boolean disabled;
}
