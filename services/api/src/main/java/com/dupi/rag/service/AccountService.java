package com.dupi.rag.service;

import com.dupi.rag.config.ApiSecurityProperties;
import com.dupi.rag.config.TenantContext;
import com.dupi.rag.dto.AccountResponse;
import com.dupi.rag.dto.AccountUpsertRequest;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AccountService {

    private final ApiSecurityProperties properties;

    public List<AccountResponse> listUsers() {
        return properties.getUsers().stream()
                .map(user -> AccountResponse.builder()
                        .username(user.getUsername())
                        .tenantId(user.getTenantId())
                        .role(normalizeRole(user.getRole()))
                        .permissions(parseCsv(user.getPermissions(), defaultPermissions(user.getRole())))
                        .knowledgeBaseIds(parseCsv(user.getKnowledgeBaseIds(), List.of()))
                        .tokenVersion(user.getTokenVersion() == null || user.getTokenVersion().isBlank() ? "1" : user.getTokenVersion().trim())
                        .passwordConfigured(user.getPassword() != null && !user.getPassword().isBlank())
                        .passwordHashConfigured(user.getPasswordHash() != null && !user.getPasswordHash().isBlank())
                        .disabled(user.isDisabled())
                        .build())
                .toList();
    }

    public AccountResponse create(AccountUpsertRequest request) {
        if (request == null || request.getUsername() == null || request.getUsername().isBlank()) {
            throw new IllegalArgumentException("username is required");
        }
        String username = request.getUsername().trim();
        if (findUser(username) != null) {
            throw new IllegalArgumentException("account already exists: " + username);
        }
        ApiSecurityProperties.UserAccount user = new ApiSecurityProperties.UserAccount();
        user.setUsername(username);
        apply(user, request, true);
        properties.getUsers().add(user);
        return response(user);
    }

    public AccountResponse update(String username, AccountUpsertRequest request) {
        ApiSecurityProperties.UserAccount user = requireUser(username);
        apply(user, request, false);
        return response(user);
    }

    public AccountResponse disable(String username) {
        ApiSecurityProperties.UserAccount user = requireUser(username);
        user.setDisabled(true);
        user.setTokenVersion(nextTokenVersion(user.getTokenVersion()));
        return response(user);
    }

    public AccountResponse enable(String username) {
        ApiSecurityProperties.UserAccount user = requireUser(username);
        user.setDisabled(false);
        return response(user);
    }

    public AccountResponse rotateTokenVersion(String username) {
        ApiSecurityProperties.UserAccount user = requireUser(username);
        user.setTokenVersion(nextTokenVersion(user.getTokenVersion()));
        return response(user);
    }

    public String generatePasswordHash(String password) {
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("password is required");
        }
        return com.dupi.rag.config.ApiTokenService.pbkdf2Hash(password, UUID.randomUUID().toString(), 120_000);
    }

    private void apply(ApiSecurityProperties.UserAccount user, AccountUpsertRequest request, boolean creating) {
        if (request == null) {
            if (creating) {
                user.setTokenVersion("1");
            }
            return;
        }
        if (request.getTenantId() != null) {
            user.setTenantId(request.getTenantId().isBlank() ? TenantContext.DEFAULT_TENANT_ID : request.getTenantId().trim());
        }
        if (request.getRole() != null) {
            user.setRole(normalizeRole(request.getRole()));
        }
        if (request.getPermissions() != null) {
            user.setPermissions(String.join(",", normalizePermissions(request.getPermissions(), user.getRole())));
        }
        if (request.getKnowledgeBaseIds() != null) {
            user.setKnowledgeBaseIds("ADMIN".equals(normalizeRole(user.getRole()))
                    ? ""
                    : String.join(",", normalizeKnowledgeBaseIds(request.getKnowledgeBaseIds())));
        }
        if (request.getTokenVersion() != null) {
            user.setTokenVersion(request.getTokenVersion().isBlank() ? "1" : request.getTokenVersion().trim());
        } else if (creating) {
            user.setTokenVersion("1");
        }
        if (request.getPasswordHash() != null && !request.getPasswordHash().isBlank()) {
            user.setPassword("");
            user.setPasswordHash(request.getPasswordHash().trim());
        } else if (request.getPassword() != null && !request.getPassword().isBlank()) {
            user.setPassword("");
            user.setPasswordHash(generatePasswordHash(request.getPassword()));
        }
        if (request.getDisabled() != null) {
            user.setDisabled(request.getDisabled());
        }
    }

    private ApiSecurityProperties.UserAccount findUser(String username) {
        if (username == null || properties.getUsers() == null) {
            return null;
        }
        return properties.getUsers().stream()
                .filter(user -> username.trim().equals(user.getUsername()))
                .findFirst()
                .orElse(null);
    }

    private ApiSecurityProperties.UserAccount requireUser(String username) {
        ApiSecurityProperties.UserAccount user = findUser(username);
        if (user == null) {
            throw new IllegalArgumentException("account not found: " + username);
        }
        return user;
    }

    private AccountResponse response(ApiSecurityProperties.UserAccount user) {
        return AccountResponse.builder()
                .username(user.getUsername())
                .tenantId(user.getTenantId())
                .role(normalizeRole(user.getRole()))
                .permissions(parseCsv(user.getPermissions(), defaultPermissions(user.getRole())))
                .knowledgeBaseIds("ADMIN".equals(normalizeRole(user.getRole())) ? List.of() : parseCsv(user.getKnowledgeBaseIds(), List.of()))
                .tokenVersion(user.getTokenVersion() == null || user.getTokenVersion().isBlank() ? "1" : user.getTokenVersion().trim())
                .passwordConfigured(user.getPassword() != null && !user.getPassword().isBlank())
                .passwordHashConfigured(user.getPasswordHash() != null && !user.getPasswordHash().isBlank())
                .disabled(user.isDisabled())
                .build();
    }

    private List<String> defaultPermissions(String role) {
        return "ADMIN".equals(normalizeRole(role)) ? List.of("*") : List.of("KB_READ", "DOCUMENT_UPLOAD", "CHAT_WRITE");
    }

    private List<String> parseCsv(String raw, List<String> fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private List<String> normalizePermissions(List<String> permissions, String role) {
        if ("ADMIN".equals(normalizeRole(role))) {
            return List.of("*");
        }
        if (permissions == null || permissions.isEmpty()) {
            return defaultPermissions(role);
        }
        return permissions.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toUpperCase().replace('-', '_'))
                .distinct()
                .toList();
    }

    private List<String> normalizeKnowledgeBaseIds(List<String> knowledgeBaseIds) {
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            return List.of();
        }
        return knowledgeBaseIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private String nextTokenVersion(String current) {
        try {
            return String.valueOf(Integer.parseInt(current == null || current.isBlank() ? "1" : current.trim()) + 1);
        } catch (NumberFormatException ex) {
            return UUID.randomUUID().toString();
        }
    }

    private String normalizeRole(String role) {
        return role == null || role.isBlank() ? "USER" : role.trim().toUpperCase();
    }
}
