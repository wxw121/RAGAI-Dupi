package com.dupi.rag.service;

import com.dupi.rag.config.ApiSecurityProperties;
import com.dupi.rag.dto.AccountUpsertRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

class AccountServiceTest {

    @Test
    void listUsersReturnsSanitizedAccountMetadataWithoutSecrets() {
        ApiSecurityProperties properties = new ApiSecurityProperties();
        ApiSecurityProperties.UserAccount user = new ApiSecurityProperties.UserAccount();
        user.setUsername("analyst");
        user.setPassword("plain-secret");
        user.setPasswordHash("pbkdf2$1000$salt$hash");
        user.setTenantId("tenant-a");
        user.setRole("USER");
        user.setPermissions("KB_READ,DOCUMENT_UPLOAD");
        user.setKnowledgeBaseIds("kb-1,kb-2");
        user.setTokenVersion("7");
        properties.getUsers().add(user);

        var users = new AccountService(properties).listUsers();

        assertThat(users).hasSize(1);
        var response = users.get(0);
        assertThat(response.getUsername()).isEqualTo("analyst");
        assertThat(response.getTenantId()).isEqualTo("tenant-a");
        assertThat(response.getRole()).isEqualTo("USER");
        assertThat(response.getPermissions()).containsExactly("KB_READ", "DOCUMENT_UPLOAD");
        assertThat(response.getKnowledgeBaseIds()).containsExactly("kb-1", "kb-2");
        assertThat(response.getTokenVersion()).isEqualTo("7");
        assertThat(response.isPasswordConfigured()).isTrue();
        assertThat(response.isPasswordHashConfigured()).isTrue();
        assertThat(response.toString()).doesNotContain("plain-secret").doesNotContain("pbkdf2");
    }

    @Test
    void createsUpdatesDisablesEnablesAndRotatesConfiguredAccounts() {
        ApiSecurityProperties properties = new ApiSecurityProperties();
        AccountService service = new AccountService(properties);
        AccountUpsertRequest create = new AccountUpsertRequest();
        create.setUsername("analyst");
        create.setPassword("new-secret");
        create.setTenantId("tenant-a");
        create.setRole("user");
        create.setPermissions(List.of("kb-read", "document_upload", "kb-read"));
        create.setKnowledgeBaseIds(List.of("kb-1", "kb-2", "kb-1"));

        var created = service.create(create);

        assertThat(created.getUsername()).isEqualTo("analyst");
        assertThat(created.getRole()).isEqualTo("USER");
        assertThat(created.getPermissions()).containsExactly("KB_READ", "DOCUMENT_UPLOAD");
        assertThat(created.getKnowledgeBaseIds()).containsExactly("kb-1", "kb-2");
        assertThat(created.getTokenVersion()).isEqualTo("1");
        assertThat(created.isDisabled()).isFalse();
        assertThat(created.isPasswordConfigured()).isFalse();
        assertThat(created.isPasswordHashConfigured()).isTrue();
        assertThat(properties.getUsers().get(0).getPassword()).isBlank();
        assertThat(properties.getUsers().get(0).getPasswordHash()).startsWith("pbkdf2$");

        AccountUpsertRequest update = new AccountUpsertRequest();
        update.setTenantId("tenant-b");
        update.setRole("ADMIN");
        update.setPermissions(List.of("ops-admin"));
        update.setKnowledgeBaseIds(List.of("kb-3"));
        var updated = service.update("analyst", update);
        assertThat(updated.getTenantId()).isEqualTo("tenant-b");
        assertThat(updated.getRole()).isEqualTo("ADMIN");
        assertThat(updated.getPermissions()).containsExactly("*");
        assertThat(updated.getKnowledgeBaseIds()).isEmpty();

        var disabled = service.disable("analyst");
        assertThat(disabled.isDisabled()).isTrue();
        assertThat(disabled.getTokenVersion()).isEqualTo("2");

        var enabled = service.enable("analyst");
        assertThat(enabled.isDisabled()).isFalse();

        var rotated = service.rotateTokenVersion("analyst");
        assertThat(rotated.getTokenVersion()).isEqualTo("3");
    }

    @Test
    void rejectsDuplicateAndMissingAccounts() {
        ApiSecurityProperties properties = new ApiSecurityProperties();
        AccountService service = new AccountService(properties);
        AccountUpsertRequest create = new AccountUpsertRequest();
        create.setUsername("analyst");
        create.setPasswordHash("pbkdf2$1000$salt$hash");

        service.create(create);

        assertThatThrownBy(() -> service.create(create))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
        assertThatThrownBy(() -> service.disable("missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void rejectsMissingUsernameAndBlankPasswordHashRequest() {
        AccountService service = new AccountService(new ApiSecurityProperties());
        AccountUpsertRequest missingUsername = new AccountUpsertRequest();
        missingUsername.setPassword("secret");

        assertThatThrownBy(() -> service.create(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("username is required");
        assertThatThrownBy(() -> service.create(missingUsername))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("username is required");
        assertThatThrownBy(() -> service.generatePasswordHash(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("password is required");
    }

    @Test
    void appliesDefaultsAndSanitizesBlankCollectionInputs() {
        ApiSecurityProperties properties = new ApiSecurityProperties();
        AccountService service = new AccountService(properties);
        AccountUpsertRequest create = new AccountUpsertRequest();
        create.setUsername("analyst");
        create.setPasswordHash("pbkdf2$1000$salt$hash");
        create.setTenantId(" ");
        create.setPermissions(List.of());
        create.setKnowledgeBaseIds(java.util.Arrays.asList(" ", "kb-1", null, "kb-1"));
        create.setTokenVersion(" ");
        create.setDisabled(true);

        var created = service.create(create);

        assertThat(created.getTenantId()).isEqualTo("default");
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
    void rotatesNonNumericTokenVersionToOpaqueVersion() {
        ApiSecurityProperties properties = new ApiSecurityProperties();
        ApiSecurityProperties.UserAccount user = new ApiSecurityProperties.UserAccount();
        user.setUsername("analyst");
        user.setTokenVersion("legacy");
        properties.getUsers().add(user);

        var rotated = new AccountService(properties).rotateTokenVersion(" analyst ");

        assertThat(rotated.getTokenVersion()).isNotBlank().isNotEqualTo("legacy");
    }

    @Test
    void generatesPbkdf2PasswordHashWithoutStoringThePassword() {
        String hash = new AccountService(new ApiSecurityProperties()).generatePasswordHash("secret");

        assertThat(hash).startsWith("pbkdf2$120000$");
        assertThat(hash).doesNotContain("secret");
    }
}
