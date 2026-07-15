package com.dupi.rag.service;

import com.dupi.rag.domain.entity.Role;
import com.dupi.rag.dto.RoleRequest;
import com.dupi.rag.repository.RoleRepository;
import com.dupi.rag.repository.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoleServiceTest {

    @Mock RoleRepository roleRepository;
    @Mock UserAccountRepository userAccountRepository;

    private RoleService service() {
        return new RoleService(roleRepository, userAccountRepository);
    }

    @Test
    void listRolesMapsPermissionsAndUserCounts() {
        Role admin = role("ADMIN", "管理员", "built in", "", true, false);
        Role analyst = role("ANALYST", "分析员", "default", "kb-read, chat_write", false, false);
        when(roleRepository.findAll()).thenReturn(List.of(admin, analyst));
        when(userAccountRepository.countByRoleCodeAndDisabledFalse("ADMIN")).thenReturn(1L);
        when(userAccountRepository.countByRoleCodeAndDisabledFalse("ANALYST")).thenReturn(2L);

        var roles = service().listRoles();

        assertThat(roles).hasSize(2);
        assertThat(roles.get(0).getPermissions()).containsExactly("*");
        assertThat(roles.get(0).getUserCount()).isEqualTo(1L);
        assertThat(roles.get(1).getPermissions()).containsExactly("KB_READ", "CHAT_WRITE");
        assertThat(roles.get(1).getUserCount()).isEqualTo(2L);
    }

    @Test
    void createNormalizesAndPersistsCustomRole() {
        RoleRequest request = new RoleRequest();
        request.setCode("support-team");
        request.setName(" Support ");
        request.setDescription(" Help desk ");
        request.setPermissions(Arrays.asList("kb-read", "KB_READ", " ", null, "chat_write"));
        request.setDisabled(true);
        when(roleRepository.existsByCode("SUPPORT_TEAM")).thenReturn(false);
        when(roleRepository.save(any(Role.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service().create(request);

        assertThat(response.getCode()).isEqualTo("SUPPORT_TEAM");
        assertThat(response.getName()).isEqualTo("Support");
        assertThat(response.getDescription()).isEqualTo("Help desk");
        assertThat(response.getPermissions()).containsExactly("KB_READ", "CHAT_WRITE");
        assertThat(response.isDisabled()).isTrue();
    }

    @Test
    void createRejectsMissingAndDuplicateCode() {
        RoleRequest blank = new RoleRequest();
        blank.setCode(" ");
        assertThatThrownBy(() -> service().create(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("role code is required");
        assertThatThrownBy(() -> service().create(blank))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("role code is required");

        RoleRequest duplicate = new RoleRequest();
        duplicate.setCode("admin");
        when(roleRepository.existsByCode("ADMIN")).thenReturn(true);
        assertThatThrownBy(() -> service().create(duplicate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("role already exists");
        verify(roleRepository, never()).save(any());
    }

    @Test
    void updateMutatesEditableFieldsAndPreservesWhenRequestIsNull() {
        UUID roleId = UUID.randomUUID();
        Role role = role("SUPPORT", "Old", "old desc", "KB_READ", false, false);
        RoleRequest request = new RoleRequest();
        request.setName(" New ");
        request.setDescription(" ");
        request.setPermissions(List.of("document-upload"));
        request.setDisabled(true);
        when(roleRepository.findById(roleId)).thenReturn(Optional.of(role));
        when(roleRepository.save(role)).thenAnswer(invocation -> invocation.getArgument(0));
        when(userAccountRepository.countByRoleCodeAndDisabledFalse("SUPPORT")).thenReturn(3L);

        var response = service().update(roleId, request);

        assertThat(role.getName()).isEqualTo("New");
        assertThat(role.getDescription()).isNull();
        assertThat(role.getPermissions()).isEqualTo("DOCUMENT_UPLOAD");
        assertThat(role.isDisabled()).isTrue();
        assertThat(response.getUserCount()).isEqualTo(3L);

        var unchanged = service().update(roleId, null);
        assertThat(unchanged.getName()).isEqualTo("New");
    }

    @Test
    void updateRejectsMissingRoleAndAdminDisable() {
        UUID missingId = UUID.randomUUID();
        when(roleRepository.findById(missingId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().update(missingId, new RoleRequest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("role not found");

        UUID adminId = UUID.randomUUID();
        Role admin = role("ADMIN", "管理员", null, "*", true, false);
        RoleRequest disable = new RoleRequest();
        disable.setDisabled(true);
        when(roleRepository.findById(adminId)).thenReturn(Optional.of(admin));
        assertThatThrownBy(() -> service().update(adminId, disable))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ADMIN role cannot be disabled");
    }

    @Test
    void disableRejectsAdminAndDisablesCustomRole() {
        UUID supportId = UUID.randomUUID();
        Role support = role("SUPPORT", "Support", null, "KB_READ", false, false);
        when(roleRepository.findById(supportId)).thenReturn(Optional.of(support));
        when(roleRepository.save(support)).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service().disable(supportId);

        assertThat(support.isDisabled()).isTrue();
        assertThat(response.isDisabled()).isTrue();

        UUID adminId = UUID.randomUUID();
        when(roleRepository.findById(adminId)).thenReturn(Optional.of(role("ADMIN", "管理员", null, "*", true, false)));
        assertThatThrownBy(() -> service().disable(adminId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ADMIN role cannot be disabled");
    }

    @Test
    void requireActiveRoleNormalizesAndRejectsDisabledOrMissingRoles() {
        Role active = role("SUPPORT_TEAM", "Support", null, "KB_READ", false, false);
        when(roleRepository.findByCode("SUPPORT_TEAM")).thenReturn(Optional.of(active));
        assertThat(service().requireActiveRole("support-team")).isSameAs(active);

        Role disabled = role("VIEWER", "Viewer", null, "KB_READ", false, true);
        when(roleRepository.findByCode("VIEWER")).thenReturn(Optional.of(disabled));
        assertThatThrownBy(() -> service().requireActiveRole("viewer"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("role is disabled");

        when(roleRepository.findByCode("UNKNOWN")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().requireActiveRole("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("role not found");
    }

    @Test
    void permissionHelpersNormalizeDefaultsAndAdmin() {
        assertThat(RoleService.normalizeCode("support-team")).isEqualTo("SUPPORT_TEAM");
        assertThat(RoleService.normalizeCode(" ")).isEqualTo("ANALYST");
        assertThat(RoleService.normalizePermissions(null, "analyst"))
                .containsExactly("KB_READ", "DOCUMENT_UPLOAD", "CHAT_WRITE");
        assertThat(RoleService.normalizePermissions(List.of("kb-read", "KB_READ", "chat_write"), "viewer"))
                .containsExactly("KB_READ", "CHAT_WRITE");
        assertThat(RoleService.normalizePermissions(List.of("KB_READ"), "admin")).containsExactly("*");
        assertThat(RoleService.parsePermissions("", "viewer"))
                .containsExactly("KB_READ", "DOCUMENT_UPLOAD", "CHAT_WRITE");
        assertThat(RoleService.parsePermissions("kb-write, chat-delete", "viewer"))
                .containsExactly("KB_WRITE", "CHAT_DELETE");
        assertThat(RoleService.parsePermissions("ignored", "ADMIN")).containsExactly("*");
    }

    private static Role role(
            String code,
            String name,
            String description,
            String permissions,
            boolean builtin,
            boolean disabled
    ) {
        return Role.builder()
                .id(UUID.randomUUID())
                .code(code)
                .name(name)
                .description(description)
                .permissions(permissions)
                .systemBuiltin(builtin)
                .disabled(disabled)
                .build();
    }
}
