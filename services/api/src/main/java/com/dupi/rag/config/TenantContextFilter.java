package com.dupi.rag.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class TenantContextFilter extends OncePerRequestFilter {

    public static final String TENANT_HEADER = "X-Dupi-Tenant-Id";

    private static final Pattern SAFE_TENANT_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");

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

        boolean tenantAlreadyBound = TenantContext.hasTenantId();
        String tenantId = tenantAlreadyBound ? TenantContext.getTenantId() : resolveTenantId(request.getHeader(TENANT_HEADER));
        if (!SAFE_TENANT_ID.matcher(tenantId).matches()) {
            reject(response);
            if (!tenantAlreadyBound) {
                TenantContext.clear();
            }
            return;
        }

        if (!tenantAlreadyBound) {
            TenantContext.setTenantId(tenantId);
        }
        response.setHeader(TENANT_HEADER, tenantId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (!tenantAlreadyBound) {
                TenantContext.clear();
            }
        }
    }

    private String resolveTenantId(String rawTenantId) {
        if (rawTenantId == null || rawTenantId.isBlank()) {
            return TenantContext.DEFAULT_TENANT_ID;
        }
        return rawTenantId.trim();
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"bad_request\",\"message\":\"Invalid tenant id\"}");
    }
}
