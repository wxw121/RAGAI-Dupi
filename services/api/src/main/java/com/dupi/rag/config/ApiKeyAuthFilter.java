package com.dupi.rag.config;

import com.dupi.rag.repository.UserAccountRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String PUBLIC_API_KEY_HEADER = "X-Dupi-API-Key";
    public static final String INTERNAL_API_KEY_HEADER = "X-Dupi-Internal-Key";
    public static final String AUTHORIZATION_HEADER = "Authorization";
    public static final String AUTH_COOKIE_NAME = "DUPI_AUTH";
    public static final String CSRF_COOKIE_NAME = "DUPI_CSRF";
    public static final String CSRF_HEADER = "X-Dupi-CSRF-Token";
    private static final String AUTH_MODE_ATTRIBUTE = ApiKeyAuthFilter.class.getName() + ".authMode";
    private static final String COOKIE_AUTH_MODE = "cookie";

    private final ApiSecurityProperties securityProperties;
    private final ApiTokenService tokenService;
    private final UserAccountRepository userAccountRepository;

    public ApiKeyAuthFilter(ApiSecurityProperties securityProperties) {
        this(securityProperties, new ApiTokenService(securityProperties), null);
    }

    @Autowired
    public ApiKeyAuthFilter(
            ApiSecurityProperties securityProperties,
            ApiTokenService tokenService,
            UserAccountRepository userAccountRepository
    ) {
        this.securityProperties = securityProperties;
        this.tokenService = tokenService;
        this.userAccountRepository = userAccountRepository;
    }

    public ApiKeyAuthFilter(ApiSecurityProperties securityProperties, ApiTokenService tokenService) {
        this(securityProperties, tokenService, null);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod()) || request.getRequestURI().startsWith("/actuator/")) {
            filterChain.doFilter(request, response);
            return;
        }

        String requestUri = request.getRequestURI();
        if (requestUri.startsWith("/api/v1/auth/login")) {
            filterChain.doFilter(request, response);
            return;
        }

        if (requestUri.startsWith("/api/v1/internal/")) {
            if (!isAllowed(request.getHeader(INTERNAL_API_KEY_HEADER), securityProperties.getInternalKey())) {
                reject(response, "Unauthorized internal API request");
                return;
            }
            SecurityContext.set("internal", "INTERNAL");
        } else if (requestUri.startsWith("/api/")) {
            boolean authenticated = authenticatePublicRequest(request, response);
            if (!authenticated) {
                reject(response, "Unauthorized API request");
                return;
            }
            String missingPermission = requiredPermissions(request).stream()
                    .filter(permission -> !SecurityContext.hasPermission(permission))
                    .findFirst()
                    .orElse("");
            if (!missingPermission.isBlank()) {
                reject(response, HttpStatus.FORBIDDEN, "forbidden", "permission required: " + missingPermission);
                return;
            }
            String requiredKnowledgeBaseId = requiredKnowledgeBaseId(request);
            if (!requiredKnowledgeBaseId.isBlank() && !SecurityContext.canAccessKnowledgeBase(requiredKnowledgeBaseId)) {
                reject(response, HttpStatus.FORBIDDEN, "forbidden", "knowledge base access required: " + requiredKnowledgeBaseId);
                return;
            }
            if (requiresCsrf(request) && isCookieAuthenticated(request) && !hasValidCsrf(request)) {
                reject(response, HttpStatus.FORBIDDEN, "forbidden", "CSRF token required");
                return;
            }
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContext.clear();
            TenantContext.clear();
        }
    }

    private boolean authenticatePublicRequest(HttpServletRequest request, HttpServletResponse response) {
        boolean apiKeyConfigured = hasText(securityProperties.getApiKey());
        if (isLocalOpenMode()) {
            SecurityContext.set("local-open", "ADMIN", List.of("*"));
            bindTenantFromHeader(request, response);
            request.setAttribute(AUTH_MODE_ATTRIBUTE, "local-open");
            return true;
        }

        String bearerToken = resolveBearerToken(request.getHeader(AUTHORIZATION_HEADER));
        if (!bearerToken.isBlank()) {
            return tokenService.parse(bearerToken)
                    .map(principal -> {
                        TenantContext.setTenantId(principal.tenantId());
                        SecurityContext.set(principal.username(), principal.role(), principal.permissions(), principal.knowledgeBaseIds());
                        response.setHeader(TenantContextFilter.TENANT_HEADER, principal.tenantId());
                        request.setAttribute(AUTH_MODE_ATTRIBUTE, "bearer");
                        return true;
                    })
                    .orElse(false);
        }

        String cookieToken = resolveCookie(request, AUTH_COOKIE_NAME);
        if (!cookieToken.isBlank()) {
            return tokenService.parse(cookieToken)
                    .map(principal -> {
                        TenantContext.setTenantId(principal.tenantId());
                        SecurityContext.set(principal.username(), principal.role(), principal.permissions(), principal.knowledgeBaseIds());
                        response.setHeader(TenantContextFilter.TENANT_HEADER, principal.tenantId());
                        request.setAttribute(AUTH_MODE_ATTRIBUTE, COOKIE_AUTH_MODE);
                        return true;
                    })
                    .orElse(false);
        }

        if (apiKeyConfigured && isAllowed(request.getHeader(PUBLIC_API_KEY_HEADER), securityProperties.getApiKey())) {
            SecurityContext.set("api-key", "ADMIN", List.of("*"));
            bindTenantFromHeader(request, response);
            request.setAttribute(AUTH_MODE_ATTRIBUTE, "api-key");
            return true;
        }

        if (apiKeyConfigured && !securityProperties.hasConfiguredUsers()) {
            return false;
        }

        return false;
    }

    private boolean isLocalOpenMode() {
        if (hasText(securityProperties.getApiKey()) || securityProperties.hasConfiguredUsers()) {
            return false;
        }
        return userAccountRepository == null || userAccountRepository.countByRoleCodeAndDisabledFalse("ADMIN") == 0;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean requiresCsrf(HttpServletRequest request) {
        return !("GET".equalsIgnoreCase(request.getMethod())
                || "HEAD".equalsIgnoreCase(request.getMethod())
                || "OPTIONS".equalsIgnoreCase(request.getMethod()));
    }

    private boolean isCookieAuthenticated(HttpServletRequest request) {
        return COOKIE_AUTH_MODE.equals(request.getAttribute(AUTH_MODE_ATTRIBUTE));
    }

    private boolean hasValidCsrf(HttpServletRequest request) {
        String csrfCookie = resolveCookie(request, CSRF_COOKIE_NAME);
        String csrfHeader = request.getHeader(CSRF_HEADER);
        return csrfCookie != null
                && !csrfCookie.isBlank()
                && csrfHeader != null
                && !csrfHeader.isBlank()
                && MessageDigest.isEqual(
                csrfCookie.getBytes(StandardCharsets.UTF_8),
                csrfHeader.getBytes(StandardCharsets.UTF_8)
        );
    }

    private String resolveCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null || cookies.length == 0) {
            return "";
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue() == null ? "" : cookie.getValue();
            }
        }
        return "";
    }

    private List<String> requiredPermissions(HttpServletRequest request) {
        String method = request.getMethod();
        String uri = request.getRequestURI();
        // 集中式路由权限映射：先在过滤器层拦截高风险动作，避免控制器遗漏权限判断。
        // 当前设计是轻量 RBAC + 权限点模型，ADMIN 在 SecurityContext 中映射为通配权限。
        if ("POST".equalsIgnoreCase(method) && "/api/v1/ops/audit-alerts/notify".equals(uri)) {
            return List.of("OPS_ADMIN", "OPS_AUDIT_READ", "OPS_ALERT_NOTIFY");
        }
        if (uri.startsWith("/api/v1/ops/")) {
            return List.of("OPS_ADMIN");
        }
        if ("POST".equalsIgnoreCase(method) && "/api/v1/knowledge-bases/import".equals(uri)) {
            return List.of("KB_WRITE");
        }
        if (uri.contains("/retrieval-profiles")) {
            if ("POST".equalsIgnoreCase(method)
                    && (uri.endsWith("/activate") || uri.endsWith("/rollback"))) {
                return List.of("OPS_ADMIN", "KB_READ");
            }
            if ("POST".equalsIgnoreCase(method)) {
                return List.of("KB_WRITE");
            }
        }
        if (uri.contains("/rag-eval/")) {
            if ("POST".equalsIgnoreCase(method) && uri.endsWith("/baseline")) {
                return List.of("OPS_ADMIN", "KB_READ");
            }
            if ("POST".equalsIgnoreCase(method) && uri.endsWith("/runs")) {
                return List.of("MAINTENANCE", "KB_READ");
            }
            if ("POST".equalsIgnoreCase(method)
                    || "PATCH".equalsIgnoreCase(method)
                    || "DELETE".equalsIgnoreCase(method)) {
                return List.of("KB_WRITE");
            }
        }
        if ("DELETE".equalsIgnoreCase(method) && uri.startsWith("/api/v1/knowledge-bases/")) {
            if (uri.contains("/documents/")) {
                return List.of("DOCUMENT_DELETE");
            }
            if (uri.contains("/chat-sessions")) {
                return List.of("CHAT_DELETE");
            }
            return List.of("KB_DELETE");
        }
        if ("POST".equalsIgnoreCase(method) && uri.matches(".*/ingest-jobs/[^/]+/retry$")) {
            return List.of("MAINTENANCE");
        }
        if ("POST".equalsIgnoreCase(method) && uri.endsWith("/reindex")) {
            return List.of("MAINTENANCE");
        }
        if ("POST".equalsIgnoreCase(method) && uri.contains("/documents")) {
            return List.of("DOCUMENT_UPLOAD");
        }
        if ("POST".equalsIgnoreCase(method) && uri.endsWith("/chat")) {
            return List.of("CHAT_WRITE");
        }
        if ("POST".equalsIgnoreCase(method) && uri.endsWith("/retrieve")) {
            return List.of("KB_READ");
        }
        if ("GET".equalsIgnoreCase(method) && uri.startsWith("/api/v1/knowledge-bases")) {
            return List.of("KB_READ");
        }
        if ("POST".equalsIgnoreCase(method) && "/api/v1/knowledge-bases".equals(uri)) {
            return List.of("KB_WRITE");
        }
        return List.of();
    }

    private String requiredKnowledgeBaseId(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if ("/api/v1/knowledge-bases/import".equals(uri)) {
            return "";
        }
        String prefix = "/api/v1/knowledge-bases/";
        if (!uri.startsWith(prefix)) {
            return "";
        }
        String remaining = uri.substring(prefix.length());
        int slash = remaining.indexOf('/');
        String kbId = slash >= 0 ? remaining.substring(0, slash) : remaining;
        return kbId == null ? "" : kbId.trim();
    }

    private boolean isAllowed(String provided, String expected) {
        if (expected == null || expected.isBlank()) {
            return true;
        }
        if (provided == null || provided.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8)
        );
    }

    private void bindTenantFromHeader(HttpServletRequest request, HttpServletResponse response) {
        String tenantId = request.getHeader(TenantContextFilter.TENANT_HEADER);
        if (tenantId == null || tenantId.isBlank()) {
            return;
        }
        TenantContext.setTenantId(tenantId.trim());
        response.setHeader(TenantContextFilter.TENANT_HEADER, TenantContext.getTenantId());
    }

    private String resolveBearerToken(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return "";
        }
        String prefix = "Bearer ";
        if (!authorization.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return "";
        }
        return authorization.substring(prefix.length()).trim();
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        reject(response, HttpStatus.UNAUTHORIZED, "unauthorized", message);
    }

    private void reject(HttpServletResponse response, HttpStatus status, String error, String message) throws IOException {
        SecurityContext.clear();
        TenantContext.clear();
        response.setStatus(status.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + error + "\",\"message\":\"" + message + "\"}");
    }
}
