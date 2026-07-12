package com.dupi.rag.dto;

import java.time.Instant;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class LoginResponse {
    String token;
    String csrfToken;
    String username;
    String tenantId;
    String role;
    Instant expiresAt;
}
