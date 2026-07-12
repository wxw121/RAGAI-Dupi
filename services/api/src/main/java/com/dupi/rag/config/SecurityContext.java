package com.dupi.rag.config;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

public final class SecurityContext {

    private static final ThreadLocal<Principal> CURRENT_PRINCIPAL = new ThreadLocal<>();

    private SecurityContext() {
    }

    public static void set(String principal, String role) {
        set(principal, role, Set.of(), Set.of());
    }

    public static void set(String principal, String role, Collection<String> permissions) {
        set(principal, role, permissions, Set.of());
    }

    public static void set(String principal, String role, Collection<String> permissions, Collection<String> knowledgeBaseIds) {
        String normalizedRole = normalizeRole(role);
        CURRENT_PRINCIPAL.set(new Principal(
                principal,
                normalizedRole,
                normalizePermissions(permissions, normalizedRole),
                normalizeKnowledgeBaseIds(knowledgeBaseIds, normalizedRole)
        ));
    }

    public static String getPrincipal() {
        Principal principal = CURRENT_PRINCIPAL.get();
        return principal == null ? null : principal.name();
    }

    public static String getRole() {
        Principal principal = CURRENT_PRINCIPAL.get();
        return principal == null ? null : principal.role();
    }

    public static boolean hasRole(String role) {
        Principal principal = CURRENT_PRINCIPAL.get();
        return principal != null && principal.role().equals(normalizeRole(role));
    }

    public static boolean hasPermission(String permission) {
        Principal principal = CURRENT_PRINCIPAL.get();
        if (principal == null || permission == null || permission.isBlank()) {
            return false;
        }
        return principal.permissions().contains("*") || principal.permissions().contains(normalizePermission(permission));
    }

    public static boolean canAccessKnowledgeBase(String kbId) {
        Principal principal = CURRENT_PRINCIPAL.get();
        if (principal == null || kbId == null || kbId.isBlank()) {
            return false;
        }
        if (principal.permissions().contains("*") || principal.knowledgeBaseIds().isEmpty()) {
            return true;
        }
        return principal.knowledgeBaseIds().contains(kbId.trim());
    }

    public static void clear() {
        CURRENT_PRINCIPAL.remove();
    }

    private static String normalizeRole(String role) {
        return role == null || role.isBlank() ? "USER" : role.trim().toUpperCase();
    }

    private static Set<String> normalizePermissions(Collection<String> permissions, String role) {
        // ADMIN 使用通配权限，普通用户按显式权限点授权；这让角色模型保持简单，也为后续按知识库/资源授权留下扩展口。
        if ("ADMIN".equals(normalizeRole(role))) {
            return Set.of("*");
        }
        if (permissions == null || permissions.isEmpty()) {
            return Set.of("KB_READ", "DOCUMENT_UPLOAD", "CHAT_WRITE");
        }
        return permissions.stream()
                .filter(permission -> permission != null && !permission.isBlank())
                .map(SecurityContext::normalizePermission)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Set<String> normalizeKnowledgeBaseIds(Collection<String> knowledgeBaseIds, String role) {
        if ("ADMIN".equals(normalizeRole(role)) || knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            return Set.of();
        }
        return knowledgeBaseIds.stream()
                .filter(kbId -> kbId != null && !kbId.isBlank())
                .map(String::trim)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String normalizePermission(String permission) {
        return permission.trim().toUpperCase().replace('-', '_');
    }

    private record Principal(String name, String role, Set<String> permissions, Set<String> knowledgeBaseIds) {
    }
}
