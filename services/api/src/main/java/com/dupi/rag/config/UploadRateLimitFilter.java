package com.dupi.rag.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
@RequiredArgsConstructor
public class UploadRateLimitFilter extends OncePerRequestFilter {

    private static final String PUBLIC_UPLOAD_PATTERN = "/api/v1/knowledge-bases/";

    private final UploadRateLimitProperties properties;
    private final Clock clock;
    private final Map<String, WindowCounter> counters = new ConcurrentHashMap<>();

    @Autowired
    public UploadRateLimitFilter(UploadRateLimitProperties properties) {
        this(properties, Clock.systemUTC());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!shouldRateLimit(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        String key = clientKey(request);
        long window = currentWindow();
        WindowCounter counter = counters.compute(key, (ignored, existing) -> {
            if (existing == null || existing.window() != window) {
                return new WindowCounter(window, new AtomicInteger(1));
            }
            existing.count().incrementAndGet();
            return existing;
        });

        if (counter.count().get() > Math.max(1, properties.getRequests())) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json;charset=UTF-8");
            response.setHeader("Retry-After", String.valueOf(Math.max(1, properties.getWindowSeconds())));
            response.getWriter().write("{\"error\":\"rate_limited\",\"message\":\"Upload rate limit exceeded\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean shouldRateLimit(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return properties.isEnabled()
                && "POST".equalsIgnoreCase(request.getMethod())
                && uri.startsWith(PUBLIC_UPLOAD_PATTERN)
                && (uri.endsWith("/documents") || uri.endsWith("/documents/batch"));
    }

    private String clientKey(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        String ip = forwardedFor != null && !forwardedFor.isBlank()
                ? forwardedFor.split(",")[0].trim()
                : request.getRemoteAddr();
        String tenantId = TenantContext.getTenantId();
        String principal = SecurityContext.getPrincipal();
        if (principal != null && !principal.isBlank()) {
            return tenantId + ":principal:" + principal.trim();
        }
        String apiKey = request.getHeader(ApiKeyAuthFilter.PUBLIC_API_KEY_HEADER);
        return tenantId + ":anonymous:" + ip + ":" + (apiKey == null ? "" : apiKey);
    }

    private long currentWindow() {
        return clock.instant().getEpochSecond() / Math.max(1, properties.getWindowSeconds());
    }

    private record WindowCounter(long window, AtomicInteger count) {}
}
