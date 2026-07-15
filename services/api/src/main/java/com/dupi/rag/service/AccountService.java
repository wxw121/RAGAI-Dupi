package com.dupi.rag.service;

import com.dupi.rag.config.ApiSecurityProperties;
import com.dupi.rag.config.ApiTokenService;
import com.dupi.rag.config.SecurityContext;
import com.dupi.rag.config.TenantContext;
import com.dupi.rag.domain.entity.Role;
import com.dupi.rag.domain.entity.UserAccount;
import com.dupi.rag.dto.AccountResponse;
import com.dupi.rag.dto.AccountUpsertRequest;
import com.dupi.rag.repository.RoleRepository;
import com.dupi.rag.repository.UserAccountRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AccountService {

    private final ApiSecurityProperties properties;
    private final UserAccountRepository userAccountRepository;
    private final RoleRepository roleRepository;
    private final RoleService roleService;

    @PostConstruct
    @Transactional
    public void bootstrapConfiguredUsers() {
        ensureBuiltInRoles();
        if (properties.getUsers() == null || properties.getUsers().isEmpty()) {
            return;
        }
        for (ApiSecurityProperties.UserAccount configured : properties.getUsers()) {
            if (configured.getUsername() == null || configured.getUsername().isBlank()
                    || userAccountRepository.existsByUsername(configured.getUsername().trim())) {
                continue;
            }
            String roleCode = configuredRoleCode(configured);
            Role role = roleService.requireActiveRole(roleCode);
            String passwordHash = configured.getPasswordHash();
            if (passwordHash == null || passwordHash.isBlank()) {
                if (configured.getPassword() == null || configured.getPassword().isBlank()) {
                    continue;
                }
                passwordHash = generatePasswordHash(configured.getPassword());
            }
            userAccountRepository.save(UserAccount.builder()
                    .username(configured.getUsername().trim())
                    .passwordHash(passwordHash.trim())
                    .tenantId(hasText(configured.getTenantId()) ? configured.getTenantId().trim() : TenantContext.DEFAULT_TENANT_ID)
                    .roleCode(role.getCode())
                    .knowledgeBaseIds("ADMIN".equals(role.getCode()) ? "" : normalizeCsv(configured.getKnowledgeBaseIds()))
                    .tokenVersion(hasText(configured.getTokenVersion()) ? configured.getTokenVersion().trim() : "1")
                    .disabled(configured.isDisabled())
                    .build());
        }
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> listUsers() {
        return userAccountRepository.findAll().stream()
                .map(this::response)
                .toList();
    }

    @Transactional
    public AccountResponse create(AccountUpsertRequest request) {
        if (request == null || request.getUsername() == null || request.getUsername().isBlank()) {
            throw new IllegalArgumentException("username is required");
        }
        String username = request.getUsername().trim();
        if (userAccountRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("account already exists: " + username);
        }
        Role role = roleService.requireActiveRole(requestedRole(request, "ANALYST"));
        String password = request.getPassword();
        if (!hasText(password) && !hasText(request.getPasswordHash())) {
            throw new IllegalArgumentException("password is required");
        }
        UserAccount user = UserAccount.builder()
                .username(username)
                .passwordHash(hasText(request.getPasswordHash()) ? request.getPasswordHash().trim() : generatePasswordHash(password))
                .tenantId(hasText(request.getTenantId()) ? request.getTenantId().trim() : TenantContext.DEFAULT_TENANT_ID)
                .roleCode(role.getCode())
                .knowledgeBaseIds("ADMIN".equals(role.getCode()) ? "" : String.join(",", normalizeKnowledgeBaseIds(request.getKnowledgeBaseIds())))
                .tokenVersion("1")
                .disabled(Boolean.TRUE.equals(request.getDisabled()))
                .build();
        return response(userAccountRepository.save(user));
    }

    @Transactional
    public AccountResponse update(String username, AccountUpsertRequest request) {
        UserAccount user = requireUser(username);
        if (request != null) {
            if (request.getTenantId() != null) {
                user.setTenantId(request.getTenantId().isBlank() ? TenantContext.DEFAULT_TENANT_ID : request.getTenantId().trim());
            }
            if (request.getRoleCode() != null || request.getRole() != null) {
                Role role = roleService.requireActiveRole(requestedRole(request, user.getRoleCode()));
                user.setRoleCode(role.getCode());
                if ("ADMIN".equals(role.getCode())) {
                    user.setKnowledgeBaseIds("");
                }
                user.setTokenVersion(nextTokenVersion(user.getTokenVersion()));
            }
            if (request.getKnowledgeBaseIds() != null) {
                user.setKnowledgeBaseIds("ADMIN".equals(user.getRoleCode()) ? "" : String.join(",", normalizeKnowledgeBaseIds(request.getKnowledgeBaseIds())));
            }
            if (request.getDisabled() != null) {
                if (request.getDisabled()) {
                    ensureCanDisable(user);
                }
                user.setDisabled(request.getDisabled());
                user.setTokenVersion(nextTokenVersion(user.getTokenVersion()));
            }
        }
        return response(userAccountRepository.save(user));
    }

    @Transactional
    public AccountResponse resetPassword(String username, String password) {
        if (!SecurityContext.hasPermission("ACCOUNT_PASSWORD_RESET")) {
            throw new IllegalArgumentException("permission required: ACCOUNT_PASSWORD_RESET");
        }
        UserAccount user = requireUser(username);
        if (!hasText(password)) {
            throw new IllegalArgumentException("password is required");
        }
        user.setPasswordHash(generatePasswordHash(password));
        user.setTokenVersion(nextTokenVersion(user.getTokenVersion()));
        return response(userAccountRepository.save(user));
    }

    @Transactional
    public AccountResponse disable(String username) {
        UserAccount user = requireUser(username);
        ensureCanDisable(user);
        user.setDisabled(true);
        user.setTokenVersion(nextTokenVersion(user.getTokenVersion()));
        return response(userAccountRepository.save(user));
    }

    @Transactional
    public AccountResponse enable(String username) {
        UserAccount user = requireUser(username);
        user.setDisabled(false);
        return response(userAccountRepository.save(user));
    }

    @Transactional
    public AccountResponse rotateTokenVersion(String username) {
        UserAccount user = requireUser(username);
        user.setTokenVersion(nextTokenVersion(user.getTokenVersion()));
        return response(userAccountRepository.save(user));
    }

    @Transactional
    public String deleteE2e(String username) {
        UserAccount user = requireUser(username);
        if (!user.getUsername().startsWith("e2e_") || !"e2e".equals(user.getTenantId())) {
            throw new IllegalArgumentException("only e2e_* accounts in the e2e tenant can be deleted");
        }
        userAccountRepository.delete(user);
        return user.getUsername();
    }

    public String generatePasswordHash(String password) {
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("password is required");
        }
        return ApiTokenService.pbkdf2Hash(password, UUID.randomUUID().toString(), 120_000);
    }

    @Transactional(readOnly = true)
    public AccountResponse response(UserAccount user) {
        Role role = roleRepository.findByCode(user.getRoleCode()).orElse(null);
        String roleCode = role == null ? user.getRoleCode() : role.getCode();
        return AccountResponse.builder()
                .username(user.getUsername())
                .tenantId(user.getTenantId())
                .role(roleCode)
                .roleCode(roleCode)
                .roleName(role == null ? roleCode : role.getName())
                .permissions(role == null ? List.of() : RoleService.parsePermissions(role.getPermissions(), roleCode))
                .knowledgeBaseIds("ADMIN".equals(roleCode) ? List.of() : parseCsv(user.getKnowledgeBaseIds(), List.of()))
                .tokenVersion(user.getTokenVersion() == null || user.getTokenVersion().isBlank() ? "1" : user.getTokenVersion().trim())
                .passwordConfigured(false)
                .passwordHashConfigured(user.getPasswordHash() != null && !user.getPasswordHash().isBlank())
                .disabled(user.isDisabled())
                .build();
    }

    private UserAccount requireUser(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username is required");
        }
        return userAccountRepository.findByUsername(username.trim())
                .orElseThrow(() -> new IllegalArgumentException("account not found: " + username.trim()));
    }

    private void ensureCanDisable(UserAccount user) {
        if ("ADMIN".equals(user.getRoleCode()) && userAccountRepository.countByRoleCodeAndDisabledFalse("ADMIN") <= 1) {
            throw new IllegalArgumentException("cannot disable the last active admin account");
        }
    }

    private void ensureBuiltInRoles() {
        saveBuiltInRole("ADMIN", "管理员", "拥有全部系统权限", List.of("*"));
        saveBuiltInRole("OPERATOR", "运维人员", "可维护知识库、任务和审计",
                List.of("KB_READ", "DOCUMENT_UPLOAD", "MAINTENANCE", "DOCUMENT_DELETE", "CHAT_DELETE", "OPS_ADMIN", "OPS_AUDIT_READ"));
        saveBuiltInRole("ANALYST", "分析用户", "可读取知识库并发起问答",
                List.of("KB_READ", "DOCUMENT_UPLOAD", "CHAT_WRITE"));
        saveBuiltInRole("VIEWER", "只读用户", "仅可查看知识库内容", List.of("KB_READ"));
    }

    private void saveBuiltInRole(String code, String name, String description, List<String> permissions) {
        roleRepository.findByCode(code).orElseGet(() -> roleRepository.save(Role.builder()
                .code(code)
                .name(name)
                .description(description)
                .permissions(String.join(",", permissions))
                .systemBuiltin(true)
                .disabled(false)
                .build()));
    }

    private String configuredRoleCode(ApiSecurityProperties.UserAccount configured) {
        String role = RoleService.normalizeCode(configured.getRole());
        return "USER".equals(role) ? "ANALYST" : role;
    }

    private String requestedRole(AccountUpsertRequest request, String fallback) {
        if (request == null) {
            return fallback;
        }
        if (hasText(request.getRoleCode())) {
            return request.getRoleCode();
        }
        if (hasText(request.getRole())) {
            String role = RoleService.normalizeCode(request.getRole());
            return "USER".equals(role) ? "ANALYST" : role;
        }
        return fallback;
    }

    private static String normalizeCsv(String raw) {
        return String.join(",", parseCsv(raw, List.of()));
    }

    private static List<String> parseCsv(String raw, List<String> fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private static List<String> normalizeKnowledgeBaseIds(List<String> knowledgeBaseIds) {
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            return List.of();
        }
        return knowledgeBaseIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private static String nextTokenVersion(String current) {
        try {
            return String.valueOf(Integer.parseInt(current == null || current.isBlank() ? "1" : current.trim()) + 1);
        } catch (NumberFormatException ex) {
            return UUID.randomUUID().toString();
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
