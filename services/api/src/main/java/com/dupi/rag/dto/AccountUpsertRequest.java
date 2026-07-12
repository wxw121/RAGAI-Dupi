package com.dupi.rag.dto;

import java.util.List;
import lombok.Data;

@Data
public class AccountUpsertRequest {
    private String username;
    private String password;
    private String passwordHash;
    private String tenantId;
    private String role;
    private List<String> permissions;
    private List<String> knowledgeBaseIds;
    private String tokenVersion;
    private Boolean disabled;
}
