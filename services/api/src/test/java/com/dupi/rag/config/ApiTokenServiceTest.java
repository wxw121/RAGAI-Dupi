package com.dupi.rag.config;

import com.dupi.rag.domain.entity.Role;
import com.dupi.rag.domain.entity.UserAccount;
import com.dupi.rag.repository.RoleRepository;
import com.dupi.rag.repository.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiTokenServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-06T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void authenticateReturnsPrincipalWithNormalizedRoleAndExpiry() {
        ApiSecurityProperties properties = propertiesWithSecret();
        properties.getUsers().add(user("alice", "pw", "tenant-a", "admin"));
        ApiTokenService service = new ApiTokenService(properties, FIXED_CLOCK);

        var principal = service.authenticate("alice", "pw");

        assertThat(principal).isPresent();
        assertThat(principal.get().username()).isEqualTo("alice");
        assertThat(principal.get().tenantId()).isEqualTo("tenant-a");
        assertThat(principal.get().role()).isEqualTo("ADMIN");
        assertThat(principal.get().expiresAt()).isEqualTo(Instant.parse("2026-07-06T08:00:00Z"));
        assertThat(service.authenticate(null, "pw")).isEmpty();
        assertThat(service.authenticate("alice", null)).isEmpty();
        assertThat(service.authenticate("alice", "wrong")).isEmpty();
    }

    @Test
    void disabledAccountsCannotAuthenticateOrUseExistingTokens() {
        ApiSecurityProperties properties = propertiesWithSecret();
        ApiSecurityProperties.UserAccount alice = user("alice", "pw", "tenant-a", "USER");
        properties.getUsers().add(alice);
        ApiTokenService service = new ApiTokenService(properties, FIXED_CLOCK);
        String token = service.issueToken("alice", "tenant-a", "USER");

        alice.setDisabled(true);

        assertThat(service.authenticate("alice", "pw")).isEmpty();
        assertThat(service.parse(token)).isEmpty();
    }

    @Test
    void authenticateAcceptsPbkdf2PasswordHashAndLocksAfterRepeatedFailures() {
        ApiSecurityProperties properties = propertiesWithSecret();
        properties.setLoginMaxFailures(2);
        properties.setLoginLockoutSeconds(60);
        ApiSecurityProperties.UserAccount alice = user("alice", "", "tenant-a", "USER");
        alice.setPasswordHash(ApiTokenService.pbkdf2Hash("pw", "fixed-test-salt", 12_000));
        properties.getUsers().add(alice);
        ApiTokenService service = new ApiTokenService(properties, FIXED_CLOCK);

        assertThat(service.authenticate("alice", "wrong")).isEmpty();
        assertThat(service.authenticate("alice", "wrong-again")).isEmpty();
        assertThat(service.authenticate("alice", "pw")).isEmpty();

        ApiTokenService unlocked = new ApiTokenService(
                properties,
                Clock.fixed(Instant.parse("2026-07-06T00:01:01Z"), ZoneOffset.UTC)
        );
        assertThat(unlocked.authenticate("alice", "pw")).isPresent();
    }

    @Test
    void authenticateUsesRedisBackedLockoutAcrossServiceInstances() {
        ApiSecurityProperties properties = propertiesWithSecret();
        properties.setLoginMaxFailures(2);
        properties.setLoginLockoutSeconds(60);
        properties.getUsers().add(user("alice", "pw", "tenant-a", "USER"));
        RedisLoginFailureStore sharedStore = new RedisLoginFailureStore(redisTemplateBackedBy(new ConcurrentHashMap<>()));
        ApiTokenService firstInstance = new ApiTokenService(properties, FIXED_CLOCK, sharedStore);
        ApiTokenService secondInstance = new ApiTokenService(properties, FIXED_CLOCK, sharedStore);

        assertThat(firstInstance.authenticate("alice", "bad")).isEmpty();
        assertThat(firstInstance.authenticate("alice", "wrong")).isEmpty();

        assertThat(secondInstance.authenticate("alice", "pw")).isEmpty();

        ApiTokenService unlockedInstance = new ApiTokenService(
                properties,
                Clock.fixed(Instant.parse("2026-07-06T00:01:01Z"), ZoneOffset.UTC),
                sharedStore
        );
        assertThat(unlockedInstance.authenticate("alice", "pw")).isPresent();
    }

    @Test
    void redisLoginFailureStoreIgnoresBlankMalformedAndNonNumericPayloads() {
        Map<String, String> values = new ConcurrentHashMap<>();
        RedisLoginFailureStore store = new RedisLoginFailureStore(redisTemplateBackedBy(values));

        values.put("dupi:auth:login-failures:alice", "");
        assertThat(store.get("alice")).isNull();

        values.put("dupi:auth:login-failures:alice", "1");
        assertThat(store.get("alice")).isNull();

        values.put("dupi:auth:login-failures:alice", "many:bad");
        assertThat(store.get("alice")).isNull();

        values.put("dupi:auth:login-failures:alice", "3:1783296000");
        assertThat(store.get("alice").failures()).isEqualTo(3);
        assertThat(store.get("alice").lastFailureEpochSecond()).isEqualTo(1783296000L);
    }

    @Test
    void parseRejectsTokenWhenConfiguredUserTokenVersionChanges() {
        ApiSecurityProperties properties = propertiesWithSecret();
        ApiSecurityProperties.UserAccount alice = user("alice", "pw", "tenant-a", "USER");
        alice.setTokenVersion("1");
        properties.getUsers().add(alice);
        ApiTokenService service = new ApiTokenService(properties, FIXED_CLOCK);

        String token = service.issueToken("alice", "tenant-a", "USER");
        alice.setTokenVersion("2");

        assertThat(service.parse(token)).isEmpty();
    }

    @Test
    void tokenCarriesExplicitPermissionsAndRejectsInvalidPasswordHashes() {
        ApiSecurityProperties properties = propertiesWithSecret();
        ApiSecurityProperties.UserAccount analyst = user("analyst", "pw", "tenant-a", "USER");
        analyst.setPermissions("kb-read, document-delete, KB_READ");
        properties.getUsers().add(analyst);
        ApiTokenService service = new ApiTokenService(properties, FIXED_CLOCK);

        String token = service.issueToken("analyst", "tenant-a", "USER");
        var principal = service.parse(token);

        assertThat(principal).isPresent();
        assertThat(principal.get().permissions()).containsExactly("KB_READ", "DOCUMENT_DELETE");
        assertThat(service.issueToken("ghost", "", "", null, null)).isNotBlank();
        assertThat(ApiTokenService.pbkdf2Hash("pw", "salt", 1_000)).startsWith("pbkdf2$1000$");
        assertThatThrownBy(() -> ApiTokenService.pbkdf2Hash(null, "salt", 1_000))
                .isInstanceOf(IllegalArgumentException.class);

        ApiSecurityProperties.UserAccount broken = user("broken", "", "tenant-a", "USER");
        broken.setPasswordHash("bad-format");
        properties.getUsers().add(broken);
        assertThat(service.authenticate("broken", "pw")).isEmpty();

        ApiSecurityProperties.UserAccount invalidBase64 = user("invalid", "", "tenant-a", "USER");
        invalidBase64.setPasswordHash("pbkdf2$1000$not_base64!$also_bad!");
        properties.getUsers().add(invalidBase64);
        assertThat(service.authenticate("invalid", "pw")).isEmpty();
    }

    @Test
    void tokenCarriesConfiguredKnowledgeBaseScopeAndNormalizesCollections() {
        ApiSecurityProperties properties = propertiesWithSecret();
        ApiSecurityProperties.UserAccount analyst = user("analyst", "pw", "tenant-a", "USER");
        analyst.setPermissions("kb-read, document-upload");
        analyst.setKnowledgeBaseIds(" kb-a, kb-b, kb-a ");
        properties.getUsers().add(analyst);
        ApiTokenService service = new ApiTokenService(properties, FIXED_CLOCK);

        String configuredToken = service.issueToken("analyst", "tenant-a", "USER");
        var configuredPrincipal = service.parse(configuredToken);

        assertThat(configuredPrincipal).isPresent();
        assertThat(configuredPrincipal.get().knowledgeBaseIds()).containsExactly("kb-a", "kb-b");

        String explicitToken = service.issueToken(
                "external",
                "tenant-z",
                "USER",
                java.util.List.of("kb-read", "KB_READ", " "),
                java.util.Arrays.asList(" kb-x ", null, "kb-y", "kb-x"),
                " 7 "
        );
        var explicitPrincipal = service.parse(explicitToken);

        assertThat(explicitPrincipal).isPresent();
        assertThat(explicitPrincipal.get().permissions()).containsExactly("KB_READ");
        assertThat(explicitPrincipal.get().knowledgeBaseIds()).containsExactly("kb-x", "kb-y");
        assertThat(explicitPrincipal.get().tokenVersion()).isEqualTo("7");
    }

    @Test
    void authenticateUsesPersistedAccountsAndRolePermissionsWhenRepositoriesExist() {
        ApiSecurityProperties properties = propertiesWithSecret();
        UserAccountRepository userAccountRepository = mock(UserAccountRepository.class);
        RoleRepository roleRepository = mock(RoleRepository.class);
        LoginFailureStore loginFailureStore = mock(LoginFailureStore.class);
        Role analystRole = Role.builder()
                .id(UUID.randomUUID())
                .code("ANALYST")
                .name("Analyst")
                .permissions("kb-read, chat_write")
                .disabled(false)
                .build();
        UserAccount dbUser = UserAccount.builder()
                .id(UUID.randomUUID())
                .username("db-user")
                .passwordHash(ApiTokenService.pbkdf2Hash("pw", "db-salt", 1_000))
                .tenantId("tenant-db")
                .roleCode("ANALYST")
                .knowledgeBaseIds("kb-a, kb-b, kb-a")
                .tokenVersion("42")
                .disabled(false)
                .build();
        when(userAccountRepository.findByUsername("db-user")).thenReturn(Optional.of(dbUser));
        when(roleRepository.findByCode("ANALYST")).thenReturn(Optional.of(analystRole));
        ApiTokenService service = new ApiTokenService(
                properties,
                FIXED_CLOCK,
                loginFailureStore,
                userAccountRepository,
                roleRepository
        );

        var principal = service.authenticate(" db-user ", "pw");

        assertThat(principal).isPresent();
        assertThat(principal.get().username()).isEqualTo("db-user");
        assertThat(principal.get().tenantId()).isEqualTo("tenant-db");
        assertThat(principal.get().role()).isEqualTo("ANALYST");
        assertThat(principal.get().permissions()).containsExactly("KB_READ", "CHAT_WRITE");
        assertThat(principal.get().knowledgeBaseIds()).containsExactly("kb-a", "kb-b");
        assertThat(principal.get().tokenVersion()).isEqualTo("42");

        String token = service.issueToken("db-user", "tenant-db", "ANALYST");
        assertThat(service.parse(token)).isPresent();

        Role disabledRole = Role.builder()
                .id(UUID.randomUUID())
                .code("VIEWER")
                .name("Viewer")
                .permissions("KB_READ")
                .disabled(true)
                .build();
        UserAccount blocked = UserAccount.builder()
                .id(UUID.randomUUID())
                .username("blocked")
                .passwordHash(ApiTokenService.pbkdf2Hash("pw", "blocked-salt", 1_000))
                .tenantId("tenant-db")
                .roleCode("VIEWER")
                .disabled(false)
                .build();
        when(userAccountRepository.findByUsername("blocked")).thenReturn(Optional.of(blocked));
        when(roleRepository.findByCode("VIEWER")).thenReturn(Optional.of(disabledRole));
        assertThat(service.authenticate("blocked", "pw")).isEmpty();
    }

    @Test
    void alternateConstructorsWireDefaultClockAndInjectedRepositories() {
        ApiSecurityProperties properties = propertiesWithSecret();

        assertThat(new ApiTokenService(properties, mock(LoginFailureStore.class)).now()).isNotNull();
        assertThat(new ApiTokenService(
                properties,
                mock(LoginFailureStore.class),
                mock(UserAccountRepository.class),
                mock(RoleRepository.class)
        ).now()).isNotNull();
    }

    @Test
    void loginFailureProtectionCanBeDisabledAndSuccessfulLoginClearsFailures() {
        ApiSecurityProperties disabled = propertiesWithSecret();
        disabled.setLoginLockoutSeconds(0);
        disabled.getUsers().add(user("alice", "pw", "tenant-a", "USER"));
        ApiTokenService disabledService = new ApiTokenService(disabled, FIXED_CLOCK);

        assertThat(disabledService.authenticate("alice", "bad")).isEmpty();
        assertThat(disabledService.authenticate("alice", "bad")).isEmpty();
        assertThat(disabledService.authenticate("alice", "pw")).isPresent();

        ApiSecurityProperties properties = propertiesWithSecret();
        properties.setLoginMaxFailures(2);
        properties.setLoginLockoutSeconds(60);
        properties.getUsers().add(user("bob", "pw", "tenant-a", "USER"));
        ApiTokenService service = new ApiTokenService(properties, FIXED_CLOCK);

        assertThat(service.authenticate("bob", "bad")).isEmpty();
        assertThat(service.authenticate("bob", "pw")).isPresent();
        assertThat(service.authenticate("bob", "bad")).isEmpty();
        assertThat(service.authenticate("bob", "pw")).isPresent();
    }

    @Test
    void issueTokenUsesDefaultTenantAndUserRoleWhenInputsAreBlank() {
        ApiTokenService service = new ApiTokenService(propertiesWithSecret(), FIXED_CLOCK);

        String token = service.issueToken("bob", " ", " ");
        var principal = service.parse(token);

        assertThat(principal).isPresent();
        assertThat(principal.get().username()).isEqualTo("bob");
        assertThat(principal.get().tenantId()).isEqualTo(TenantContext.DEFAULT_TENANT_ID);
        assertThat(principal.get().role()).isEqualTo("USER");
    }

    @Test
    void parseRejectsMalformedTamperedExpiredAndInvalidPayloadTokens() {
        ApiSecurityProperties properties = propertiesWithSecret();
        properties.setTokenTtlSeconds(1);
        ApiTokenService issuer = new ApiTokenService(properties, FIXED_CLOCK);
        String token = issuer.issueToken("alice", "tenant-a", "USER");

        ApiTokenService expiredParser = new ApiTokenService(
                properties,
                Clock.fixed(Instant.parse("2026-07-06T00:00:01Z"), ZoneOffset.UTC)
        );

        assertThat(issuer.parse(null)).isEmpty();
        assertThat(issuer.parse("")).isEmpty();
        assertThat(issuer.parse("not-a-token")).isEmpty();
        assertThat(issuer.parse(".signature")).isEmpty();
        assertThat(issuer.parse(token + "x")).isEmpty();
        assertThat(issuer.parse("bad-base64." + token.substring(token.indexOf('.') + 1))).isEmpty();
        assertThat(expiredParser.parse(token)).isEmpty();
    }

    @Test
    void parseRejectsPayloadsWithMissingSubjectTenantOrInvalidExpiry() {
        ApiSecurityProperties properties = propertiesWithSecret();
        ApiTokenService issuer = new ApiTokenService(properties, FIXED_CLOCK);
        String validToken = issuer.issueToken("alice", "tenant-a", "USER");
        String signature = validToken.substring(validToken.indexOf('.') + 1);

        assertThat(issuer.parse(java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"exp\":\"not-a-number\",\"sub\":\"alice\",\"tenantId\":\"tenant-a\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                + "." + signature)).isEmpty();
        assertThat(issuer.parse(java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"exp\":1783296000,\"sub\":\"\",\"tenantId\":\"tenant-a\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                + "." + signature)).isEmpty();
        assertThat(issuer.parse(java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"exp\":1783296000,\"sub\":\"alice\",\"tenantId\":\"\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                + "." + signature)).isEmpty();
    }

    @Test
    void tokenSigningRequiresAuthSecret() {
        ApiSecurityProperties properties = new ApiSecurityProperties();
        ApiTokenService service = new ApiTokenService(properties, FIXED_CLOCK);

        assertThatThrownBy(() -> service.issueToken("alice", "tenant-a", "USER"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("auth-secret");
        assertThatThrownBy(() -> service.parse("payload.signature"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("auth-secret");
    }

    private static ApiSecurityProperties propertiesWithSecret() {
        ApiSecurityProperties properties = new ApiSecurityProperties();
        properties.setAuthSecret("test-secret");
        return properties;
    }

    private static ApiSecurityProperties.UserAccount user(String username, String password, String tenantId, String role) {
        ApiSecurityProperties.UserAccount user = new ApiSecurityProperties.UserAccount();
        user.setUsername(username);
        user.setPassword(password);
        user.setTenantId(tenantId);
        user.setRole(role);
        return user;
    }

    private static StringRedisTemplate redisTemplateBackedBy(Map<String, String> values) {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(any())).thenAnswer(invocation -> values.get(invocation.getArgument(0)));
        doAnswer(invocation -> {
            values.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(valueOperations).set(eq("dupi:auth:login-failures:alice"), any(String.class), any());
        doAnswer(invocation -> values.remove(invocation.getArgument(0)) != null)
                .when(redisTemplate).delete(any(String.class));
        return redisTemplate;
    }
}
