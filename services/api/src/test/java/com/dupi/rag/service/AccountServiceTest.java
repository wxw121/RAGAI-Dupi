package com.dupi.rag.service;

import com.dupi.rag.config.ApiSecurityProperties;
import com.dupi.rag.config.SecurityContext;
import com.dupi.rag.domain.entity.Role;
import com.dupi.rag.domain.entity.UserAccount;
import com.dupi.rag.dto.AccountUpsertRequest;
import com.dupi.rag.repository.RoleRepository;
import com.dupi.rag.repository.UserAccountRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccountServiceTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContext.clear();
    }

    @Test
    void bootstrapsConfiguredUsersIntoPersistentAccountsWithoutExposingSecrets() {
        ApiSecurityProperties properties = new ApiSecurityProperties();
        ApiSecurityProperties.UserAccount configured = new ApiSecurityProperties.UserAccount();
        configured.setUsername("admin");
        configured.setPassword("plain-secret");
        configured.setTenantId("ops");
        configured.setRole("ADMIN");
        configured.setTokenVersion("7");
        properties.getUsers().add(configured);
        InMemoryStores stores = stores();
        AccountService service = service(properties, stores);

        service.bootstrapConfiguredUsers();
        var users = service.listUsers();

        assertThat(users).hasSize(1);
        var response = users.get(0);
        assertThat(response.getUsername()).isEqualTo("admin");
        assertThat(response.getTenantId()).isEqualTo("ops");
        assertThat(response.getRole()).isEqualTo("ADMIN");
        assertThat(response.getRoleCode()).isEqualTo("ADMIN");
        assertThat(response.getRoleName()).isEqualTo("管理员");
        assertThat(response.getPermissions()).containsExactly("*");
        assertThat(response.getTokenVersion()).isEqualTo("7");
        assertThat(response.isPasswordConfigured()).isFalse();
        assertThat(response.isPasswordHashConfigured()).isTrue();
        assertThat(response.toString()).doesNotContain("plain-secret").doesNotContain("pbkdf2");
    }

    @Test
    void createsUpdatesDisablesEnablesAndRotatesPersistentAccounts() {
        AccountService service = service(new ApiSecurityProperties(), stores());
        service.bootstrapConfiguredUsers();
        AccountUpsertRequest root = new AccountUpsertRequest();
        root.setUsername("root");
        root.setPassword("root-secret");
        root.setRoleCode("ADMIN");
        service.create(root);

        AccountUpsertRequest create = new AccountUpsertRequest();
        create.setUsername("analyst");
        create.setPassword("new-secret");
        create.setTenantId("tenant-a");
        create.setRoleCode("ANALYST");
        create.setKnowledgeBaseIds(List.of("kb-1", "kb-2", "kb-1"));

        var created = service.create(create);

        assertThat(created.getUsername()).isEqualTo("analyst");
        assertThat(created.getRole()).isEqualTo("ANALYST");
        assertThat(created.getPermissions()).containsExactly("KB_READ", "DOCUMENT_UPLOAD", "CHAT_WRITE");
        assertThat(created.getKnowledgeBaseIds()).containsExactly("kb-1", "kb-2");
        assertThat(created.getTokenVersion()).isEqualTo("1");
        assertThat(created.isDisabled()).isFalse();
        assertThat(created.isPasswordConfigured()).isFalse();
        assertThat(created.isPasswordHashConfigured()).isTrue();

        AccountUpsertRequest update = new AccountUpsertRequest();
        update.setTenantId("tenant-b");
        update.setRoleCode("ADMIN");
        update.setKnowledgeBaseIds(List.of("kb-3"));
        var updated = service.update("analyst", update);
        assertThat(updated.getTenantId()).isEqualTo("tenant-b");
        assertThat(updated.getRole()).isEqualTo("ADMIN");
        assertThat(updated.getPermissions()).containsExactly("*");
        assertThat(updated.getKnowledgeBaseIds()).isEmpty();
        assertThat(updated.getTokenVersion()).isEqualTo("2");

        var disabled = service.disable("analyst");
        assertThat(disabled.isDisabled()).isTrue();
        assertThat(disabled.getTokenVersion()).isEqualTo("3");

        var enabled = service.enable("analyst");
        assertThat(enabled.isDisabled()).isFalse();

        var rotated = service.rotateTokenVersion("analyst");
        assertThat(rotated.getTokenVersion()).isEqualTo("4");
    }

    @Test
    void resetsPasswordOnlyWithDedicatedPermissionAndRotatesTokenVersion() {
        AccountService service = service(new ApiSecurityProperties(), stores());
        service.bootstrapConfiguredUsers();
        AccountUpsertRequest create = new AccountUpsertRequest();
        create.setUsername("analyst");
        create.setPassword("old-secret");
        create.setRoleCode("ANALYST");
        service.create(create);

        assertThatThrownBy(() -> service.resetPassword("analyst", "new-secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ACCOUNT_PASSWORD_RESET");

        SecurityContext.set("admin", "ADMIN", List.of("*"));
        var updated = service.resetPassword("analyst", "new-secret");

        assertThat(updated.getTokenVersion()).isEqualTo("2");
        assertThat(updated.isPasswordHashConfigured()).isTrue();
    }

    @Test
    void rejectsDuplicateMissingAndLastAdminDisable() {
        InMemoryStores stores = stores();
        AccountService service = service(new ApiSecurityProperties(), stores);
        service.bootstrapConfiguredUsers();
        AccountUpsertRequest create = new AccountUpsertRequest();
        create.setUsername("admin");
        create.setPassword("secret");
        create.setRoleCode("ADMIN");

        service.create(create);

        assertThatThrownBy(() -> service.create(create))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
        assertThatThrownBy(() -> service.disable("missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
        assertThatThrownBy(() -> service.disable("admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("last active admin");
    }

    @Test
    void appliesDefaultsAndSanitizesBlankCollectionInputs() {
        AccountService service = service(new ApiSecurityProperties(), stores());
        service.bootstrapConfiguredUsers();
        AccountUpsertRequest create = new AccountUpsertRequest();
        create.setUsername("analyst");
        create.setPasswordHash("pbkdf2$1000$salt$hash");
        create.setTenantId(" ");
        create.setKnowledgeBaseIds(java.util.Arrays.asList(" ", "kb-1", null, "kb-1"));
        create.setDisabled(true);

        var created = service.create(create);

        assertThat(created.getTenantId()).isEqualTo("default");
        assertThat(created.getRole()).isEqualTo("ANALYST");
        assertThat(created.getPermissions()).containsExactly("KB_READ", "DOCUMENT_UPLOAD", "CHAT_WRITE");
        assertThat(created.getKnowledgeBaseIds()).containsExactly("kb-1");
        assertThat(created.getTokenVersion()).isEqualTo("1");
        assertThat(created.isDisabled()).isTrue();

        AccountUpsertRequest noOpUpdate = null;
        var unchanged = service.update("analyst", noOpUpdate);
        assertThat(unchanged.getTokenVersion()).isEqualTo("1");
        assertThat(unchanged.isDisabled()).isTrue();
    }

    @Test
    void generatesPbkdf2PasswordHashWithoutStoringThePassword() {
        String hash = service(new ApiSecurityProperties(), stores()).generatePasswordHash("secret");

        assertThat(hash).startsWith("pbkdf2$120000$");
        assertThat(hash).doesNotContain("secret");
        assertThatThrownBy(() -> service(new ApiSecurityProperties(), stores()).generatePasswordHash(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("password is required");
    }

    @Test
    void deletesOnlyE2ePrefixedAccountsInTheE2eTenant() {
        InMemoryStores stores = stores();
        AccountService service = service(new ApiSecurityProperties(), stores);
        service.bootstrapConfiguredUsers();

        service.create(account("e2e_account_42", "e2e"));
        service.create(account("analyst", "default"));
        service.create(account("e2e_account_default", "default"));

        assertThat(service.deleteE2e("e2e_account_42")).isEqualTo("e2e_account_42");
        assertThat(stores.users).extracting(UserAccount::getUsername)
                .doesNotContain("e2e_account_42")
                .contains("analyst", "e2e_account_default");
        assertThatThrownBy(() -> service.deleteE2e("analyst"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("e2e_*");
        assertThatThrownBy(() -> service.deleteE2e("e2e_account_default"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("e2e tenant");
    }

    private static AccountUpsertRequest account(String username, String tenantId) {
        AccountUpsertRequest request = new AccountUpsertRequest();
        request.setUsername(username);
        request.setPassword("test-secret");
        request.setTenantId(tenantId);
        request.setRoleCode("ANALYST");
        return request;
    }

    private static AccountService service(ApiSecurityProperties properties, InMemoryStores stores) {
        RoleService roleService = new RoleService(stores.roleRepository, stores.userAccountRepository);
        return new AccountService(properties, stores.userAccountRepository, stores.roleRepository, roleService);
    }

    private static InMemoryStores stores() {
        InMemoryStores stores = new InMemoryStores();
        stores.userAccountRepository = mock(UserAccountRepository.class);
        stores.roleRepository = mock(RoleRepository.class);

        when(stores.roleRepository.findAll()).thenAnswer(invocation -> stores.roles);
        when(stores.roleRepository.existsByCode(any())).thenAnswer(invocation -> stores.roles.stream()
                .anyMatch(role -> role.getCode().equals(invocation.getArgument(0))));
        when(stores.roleRepository.findByCode(any())).thenAnswer(invocation -> stores.roles.stream()
                .filter(role -> role.getCode().equals(invocation.getArgument(0)))
                .findFirst());
        when(stores.roleRepository.findById(any())).thenAnswer(invocation -> stores.roles.stream()
                .filter(role -> role.getId().equals(invocation.getArgument(0)))
                .findFirst());
        when(stores.roleRepository.save(any(Role.class))).thenAnswer(invocation -> {
            Role role = invocation.getArgument(0);
            if (role.getId() == null) {
                role.setId(UUID.randomUUID());
            }
            stores.roles.removeIf(existing -> existing.getCode().equals(role.getCode()));
            stores.roles.add(role);
            return role;
        });

        when(stores.userAccountRepository.findAll()).thenAnswer(invocation -> stores.users);
        when(stores.userAccountRepository.existsByUsername(any())).thenAnswer(invocation -> stores.users.stream()
                .anyMatch(user -> user.getUsername().equals(invocation.getArgument(0))));
        when(stores.userAccountRepository.findByUsername(any())).thenAnswer(invocation -> stores.users.stream()
                .filter(user -> user.getUsername().equals(invocation.getArgument(0)))
                .findFirst());
        when(stores.userAccountRepository.countByRoleCodeAndDisabledFalse(any())).thenAnswer(invocation -> stores.users.stream()
                .filter(user -> user.getRoleCode().equals(invocation.getArgument(0)) && !user.isDisabled())
                .count());
        when(stores.userAccountRepository.save(any(UserAccount.class))).thenAnswer(invocation -> {
            UserAccount user = invocation.getArgument(0);
            if (user.getId() == null) {
                user.setId(UUID.randomUUID());
            }
            stores.users.removeIf(existing -> existing.getUsername().equals(user.getUsername()));
            stores.users.add(user);
            return user;
        });
        doAnswer(invocation -> {
            UserAccount user = invocation.getArgument(0);
            stores.users.removeIf(existing -> existing.getUsername().equals(user.getUsername()));
            return null;
        }).when(stores.userAccountRepository).delete(any(UserAccount.class));
        return stores;
    }

    private static class InMemoryStores {
        UserAccountRepository userAccountRepository;
        RoleRepository roleRepository;
        List<UserAccount> users = new java.util.ArrayList<>();
        List<Role> roles = new java.util.ArrayList<>();
    }
}
