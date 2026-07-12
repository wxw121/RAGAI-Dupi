package com.dupi.rag.controller;

import com.dupi.rag.config.ApiTokenService;
import com.dupi.rag.config.ApiKeyAuthFilter;
import com.dupi.rag.dto.LoginRequest;
import com.dupi.rag.dto.LoginResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final ApiTokenService tokenService;

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletResponse response) {
        return tokenService.authenticate(request.getUsername(), request.getPassword())
                .map(principal -> {
                    String token;
                    try {
                        token = tokenService.issueToken(principal.username(), principal.tenantId(), principal.role());
                    } catch (IllegalStateException ex) {
                        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), ex);
                    }
                    String csrfToken = generateCsrfToken();
                    Duration maxAge = Duration.between(tokenService.now(), principal.expiresAt());
                    addCookie(response, ApiKeyAuthFilter.AUTH_COOKIE_NAME, token, true, maxAge);
                    addCookie(response, ApiKeyAuthFilter.CSRF_COOKIE_NAME, csrfToken, false, maxAge);
                    return LoginResponse.builder()
                            .csrfToken(csrfToken)
                            .username(principal.username())
                            .tenantId(principal.tenantId())
                            .role(principal.role())
                            .expiresAt(principal.expiresAt())
                            .build();
                })
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password"));
    }

    private static void addCookie(HttpServletResponse response, String name, String value, boolean httpOnly, Duration maxAge) {
        ResponseCookie cookie = ResponseCookie.from(name, value)
                .httpOnly(httpOnly)
                .secure(false)
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge.isNegative() ? Duration.ZERO : maxAge)
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }

    private static String generateCsrfToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
