package com.dupi.rag.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ApiTokenService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String PBKDF2_PREFIX = "pbkdf2";
    private static final TypeReference<Map<String, Object>> TOKEN_PAYLOAD_TYPE = new TypeReference<>() {
    };

    private final ApiSecurityProperties properties;
    private final Clock clock;
    private final LoginFailureStore loginFailureStore;

    public ApiTokenService(ApiSecurityProperties properties) {
        this(properties, Clock.systemUTC(), new InMemoryLoginFailureStore());
    }

    @Autowired
    public ApiTokenService(ApiSecurityProperties properties, LoginFailureStore loginFailureStore) {
        this(properties, Clock.systemUTC(), loginFailureStore);
    }

    public ApiTokenService(ApiSecurityProperties properties, Clock clock) {
        this(properties, clock, new InMemoryLoginFailureStore());
    }

    public ApiTokenService(ApiSecurityProperties properties, Clock clock, LoginFailureStore loginFailureStore) {
        this.properties = properties;
        this.clock = clock;
        this.loginFailureStore = loginFailureStore;
    }

    public Optional<TokenPrincipal> authenticate(String username, String password) {
        if (username == null || password == null) {
            return Optional.empty();
        }
        Optional<ApiSecurityProperties.UserAccount> matchedUser = properties.getUsers().stream()
                .filter(user -> constantTimeEquals(username, user.getUsername()))
                .findFirst();
        if (matchedUser.isEmpty() || matchedUser.get().isDisabled() || isLocked(username)) {
            return Optional.empty();
        }

        ApiSecurityProperties.UserAccount user = matchedUser.get();
        if (!matchesPassword(password, user)) {
            recordFailure(username);
            return Optional.empty();
        }

        loginFailureStore.clear(username);
        return Optional.of(toPrincipal(user, expiresAt()));
    }

    public String issueToken(String username, String tenantId, String role) {
        Optional<ApiSecurityProperties.UserAccount> configuredUser = findUser(username);
        Collection<String> permissions = configuredUser.map(user -> parsePermissions(user.getPermissions(), user.getRole())).orElse(List.of());
        Collection<String> knowledgeBaseIds = configuredUser.map(user -> parseKnowledgeBaseIds(user.getKnowledgeBaseIds())).orElse(List.of());
        String tokenVersion = configuredUser.map(ApiSecurityProperties.UserAccount::getTokenVersion).orElse("1");
        return issueToken(username, tenantId, role, permissions, knowledgeBaseIds, tokenVersion);
    }

    public String issueToken(String username, String tenantId, String role, Collection<String> permissions, String tokenVersion) {
        return issueToken(username, tenantId, role, permissions, List.of(), tokenVersion);
    }

    public String issueToken(String username, String tenantId, String role, Collection<String> permissions, Collection<String> knowledgeBaseIds, String tokenVersion) {
        ensureAuthSecretConfigured();
        Instant now = clock.instant();
        Map<String, Object> payload = Map.of(
                "sub", username,
                "tenantId", tenantId == null || tenantId.isBlank() ? TenantContext.DEFAULT_TENANT_ID : tenantId,
                "role", normalizeRole(role),
                "permissions", normalizePermissions(permissions, role),
                "knowledgeBaseIds", normalizeKnowledgeBaseIds(knowledgeBaseIds),
                "ver", normalizeTokenVersion(tokenVersion),
                "iat", now.getEpochSecond(),
                "exp", now.plusSeconds(properties.getTokenTtlSeconds()).getEpochSecond()
        );
        String encodedPayload = base64Url(toJson(payload).getBytes(StandardCharsets.UTF_8));
        return encodedPayload + "." + sign(encodedPayload);
    }

    public Optional<TokenPrincipal> parse(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return Optional.empty();
        }
        if (!constantTimeEquals(sign(parts[0]), parts[1])) {
            return Optional.empty();
        }
        try {
            String json = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            Map<String, Object> payload = OBJECT_MAPPER.readValue(json, TOKEN_PAYLOAD_TYPE);
            long exp = asLong(payload.get("exp"));
            if (clock.instant().getEpochSecond() >= exp) {
                return Optional.empty();
            }
            String username = String.valueOf(payload.getOrDefault("sub", ""));
            String tenantId = String.valueOf(payload.getOrDefault("tenantId", TenantContext.DEFAULT_TENANT_ID));
            String role = normalizeRole(String.valueOf(payload.getOrDefault("role", "USER")));
            String tokenVersion = normalizeTokenVersion(String.valueOf(payload.getOrDefault("ver", "1")));
            if (username.isBlank() || tenantId.isBlank()) {
                return Optional.empty();
            }
            Optional<ApiSecurityProperties.UserAccount> configuredUser = findUser(username);
            if (configuredUser.isPresent() && configuredUser.get().isDisabled()) {
                return Optional.empty();
            }
            if (configuredUser.isPresent() && !constantTimeEquals(tokenVersion, normalizeTokenVersion(configuredUser.get().getTokenVersion()))) {
                return Optional.empty();
            }
            Collection<String> permissions = payload.containsKey("permissions")
                    ? parsePermissions(payload.get("permissions"), role)
                    : configuredUser.map(user -> parsePermissions(user.getPermissions(), role)).orElse(List.of());
            Collection<String> knowledgeBaseIds = payload.containsKey("knowledgeBaseIds")
                    ? parseKnowledgeBaseIds(payload.get("knowledgeBaseIds"))
                    : configuredUser.map(user -> parseKnowledgeBaseIds(user.getKnowledgeBaseIds())).orElse(List.of());
            return Optional.of(new TokenPrincipal(
                    username,
                    tenantId,
                    role,
                    Instant.ofEpochSecond(exp),
                    normalizePermissions(permissions, role),
                    normalizeKnowledgeBaseIds(knowledgeBaseIds),
                    tokenVersion
            ));
        } catch (IllegalArgumentException | JsonProcessingException ex) {
            return Optional.empty();
        }
    }

    public Instant expiresAt() {
        return clock.instant().plusSeconds(properties.getTokenTtlSeconds());
    }

    public Instant now() {
        return clock.instant();
    }

    private String sign(String encodedPayload) {
        ensureAuthSecretConfigured();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getAuthSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return base64Url(mac.doFinal(encodedPayload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to sign API token", ex);
        }
    }

    private void ensureAuthSecretConfigured() {
        if (properties.getAuthSecret() == null || properties.getAuthSecret().isBlank()) {
            throw new IllegalStateException("dupi.security.auth-secret is required when account login is enabled");
        }
    }

    private static String toJson(Map<String, Object> payload) {
        try {
            return OBJECT_MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize API token payload", ex);
        }
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private Optional<ApiSecurityProperties.UserAccount> findUser(String username) {
        if (username == null || properties.getUsers() == null) {
            return Optional.empty();
        }
        return properties.getUsers().stream()
                .filter(user -> constantTimeEquals(username, user.getUsername()))
                .findFirst();
    }

    private TokenPrincipal toPrincipal(ApiSecurityProperties.UserAccount user, Instant expiresAt) {
        String role = normalizeRole(user.getRole());
        String tokenVersion = normalizeTokenVersion(user.getTokenVersion());
        return new TokenPrincipal(
                user.getUsername(),
                user.getTenantId(),
                role,
                expiresAt,
                parsePermissions(user.getPermissions(), role),
                parseKnowledgeBaseIds(user.getKnowledgeBaseIds()),
                tokenVersion
        );
    }

    /**
     * 生产环境优先使用 PBKDF2 哈希密码，明文 password 仅作为本地首次启动的兼容入口。
     * 这里采用“策略优先级”思想：账号显式配置 passwordHash 时走强校验，否则回退旧字段，避免升级时破坏本地开发体验。
     */
    private boolean matchesPassword(String password, ApiSecurityProperties.UserAccount user) {
        if (user.getPasswordHash() != null && !user.getPasswordHash().isBlank()) {
            return verifyPbkdf2Password(password, user.getPasswordHash());
        }
        return constantTimeEquals(password, user.getPassword());
    }

    /**
     * 通过 LoginFailureStore 抽象读取登录失败状态：生产实现落 Redis，单元测试可注入内存实现。
     * 这里使用“策略接口 + 共享状态”的设计思想，将锁定判断与具体存储解耦，避免多副本部署时各实例独立计数。
     */
    private boolean isLocked(String username) {
        LoginFailureStore.LoginFailureState failure = loginFailureStore.get(username);
        long lockoutSeconds = properties.getLoginLockoutSeconds();
        if (failure == null || lockoutSeconds <= 0 || failure.failures() < properties.getLoginMaxFailures()) {
            return false;
        }
        long unlockAt = failure.lastFailureEpochSecond() + lockoutSeconds;
        if (clock.instant().getEpochSecond() > unlockAt) {
            loginFailureStore.clear(username);
            return false;
        }
        return true;
    }

    private void recordFailure(String username) {
        if (properties.getLoginMaxFailures() <= 0 || properties.getLoginLockoutSeconds() <= 0) {
            return;
        }
        long now = clock.instant().getEpochSecond();
        LoginFailureStore.LoginFailureState existing = loginFailureStore.get(username);
        int failures = existing == null ? 1 : existing.failures() + 1;
        loginFailureStore.save(
                username,
                new LoginFailureStore.LoginFailureState(failures, now),
                Duration.ofSeconds(properties.getLoginLockoutSeconds())
        );
    }

    private static String normalizeRole(String role) {
        return role == null || role.isBlank() ? "USER" : role.trim().toUpperCase();
    }

    private static String normalizeTokenVersion(String tokenVersion) {
        return tokenVersion == null || tokenVersion.isBlank() ? "1" : tokenVersion.trim();
    }

    public static String pbkdf2Hash(String password, String salt, int iterations) {
        if (password == null || salt == null || salt.isBlank() || iterations <= 0) {
            throw new IllegalArgumentException("password, salt and iterations are required");
        }
        byte[] saltBytes = salt.getBytes(StandardCharsets.UTF_8);
        byte[] hash = pbkdf2(password.toCharArray(), saltBytes, iterations);
        return PBKDF2_PREFIX + "$" + iterations + "$" + base64Url(saltBytes) + "$" + base64Url(hash);
    }

    private static boolean verifyPbkdf2Password(String password, String storedHash) {
        String[] parts = storedHash.split("\\$");
        if (parts.length != 4 || !PBKDF2_PREFIX.equalsIgnoreCase(parts[0])) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getUrlDecoder().decode(parts[2]);
            byte[] expected = Base64.getUrlDecoder().decode(parts[3]);
            byte[] actual = pbkdf2(password.toCharArray(), salt, iterations);
            return MessageDigest.isEqual(actual, expected);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
            try {
                return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            } finally {
                spec.clearPassword();
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to verify password hash", ex);
        }
    }

    private static List<String> parsePermissions(Object permissions, String role) {
        if ("ADMIN".equals(normalizeRole(role))) {
            return List.of("*");
        }
        if (permissions instanceof Collection<?> collection) {
            return normalizePermissions(collection.stream().map(String::valueOf).toList(), role);
        }
        String rawPermissions = permissions == null ? "" : String.valueOf(permissions);
        if (rawPermissions.isBlank()) {
            return List.of("KB_READ", "DOCUMENT_UPLOAD", "CHAT_WRITE");
        }
        return normalizePermissions(Arrays.stream(rawPermissions.split(",")).toList(), role);
    }

    private static List<String> normalizePermissions(Collection<String> permissions, String role) {
        if ("ADMIN".equals(normalizeRole(role))) {
            return List.of("*");
        }
        if (permissions == null || permissions.isEmpty()) {
            return List.of("KB_READ", "DOCUMENT_UPLOAD", "CHAT_WRITE");
        }
        return permissions.stream()
                .filter(permission -> permission != null && !permission.isBlank())
                .map(ApiTokenService::normalizePermission)
                .distinct()
                .toList();
    }

    private static String normalizePermission(String permission) {
        return permission.trim().toUpperCase().replace('-', '_');
    }

    private static List<String> parseKnowledgeBaseIds(Object knowledgeBaseIds) {
        if (knowledgeBaseIds instanceof Collection<?> collection) {
            return normalizeKnowledgeBaseIds(collection.stream().map(String::valueOf).toList());
        }
        String raw = knowledgeBaseIds == null ? "" : String.valueOf(knowledgeBaseIds);
        if (raw.isBlank()) {
            return List.of();
        }
        return normalizeKnowledgeBaseIds(Arrays.stream(raw.split(",")).toList());
    }

    private static List<String> normalizeKnowledgeBaseIds(Collection<String> knowledgeBaseIds) {
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            return List.of();
        }
        return knowledgeBaseIds.stream()
                .filter(kbId -> kbId != null && !kbId.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private static class InMemoryLoginFailureStore implements LoginFailureStore {
        private final Map<String, LoginFailureState> failures = new ConcurrentHashMap<>();

        @Override
        public LoginFailureState get(String username) {
            return failures.get(username);
        }

        @Override
        public void save(String username, LoginFailureState state, Duration ttl) {
            failures.put(username, state);
        }

        @Override
        public void clear(String username) {
            failures.remove(username);
        }
    }

    public record TokenPrincipal(String username, String tenantId, String role, Instant expiresAt,
                                 List<String> permissions, List<String> knowledgeBaseIds, String tokenVersion) {
    }
}
