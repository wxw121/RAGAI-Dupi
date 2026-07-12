package com.dupi.rag.dto;

import java.util.List;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
public class AccountResponse {
    private String username;
    private String tenantId;
    private String role;
    private String roleCode;
    private String roleName;
    private List<String> permissions;
    private List<String> knowledgeBaseIds;
    private String tokenVersion;
    private boolean passwordConfigured;
    private boolean passwordHashConfigured;
    private boolean disabled;
}
