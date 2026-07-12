package com.dupi.rag.service;

import com.dupi.rag.domain.entity.Role;
import com.dupi.rag.dto.PermissionMetadataResponse;
import com.dupi.rag.dto.RoleRequest;
import com.dupi.rag.dto.RoleResponse;
import com.dupi.rag.repository.RoleRepository;
import com.dupi.rag.repository.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RoleService {

    public static final List<String> DEFAULT_PERMISSIONS = List.of(
            "*",
            "OPS_ADMIN",
            "OPS_AUDIT_READ",
            "ACCOUNT_MANAGE",
            "ACCOUNT_PASSWORD_RESET",
            "ROLE_MANAGE",
            "KB_READ",
            "KB_WRITE",
            "KB_DELETE",
            "DOCUMENT_UPLOAD",
            "DOCUMENT_DELETE",
            "CHAT_WRITE",
            "CHAT_DELETE",
            "MAINTENANCE"
    );

    public static final List<PermissionMetadataResponse> DEFAULT_PERMISSION_DETAILS = List.of(
            permission("*", "超级管理员", "绕过权限点检查，拥有系统内全部操作能力。",
                    List.of("访问和操作全部运维、账号、角色、知识库、文档、会话与维护功能"),
                    List.of("不受知识库范围限制")),
            permission("OPS_ADMIN", "运维入口", "允许进入运维管理入口。",
                    List.of("访问运维管理页面和运维导航"),
                    List.of("不能替代账号、角色、审计或维护等具体业务权限")),
            permission("OPS_AUDIT_READ", "审计查看", "允许查看审计记录和审计告警。",
                    List.of("查看审计日志", "导出审计日志", "查看审计告警"),
                    List.of("不能修改账号、角色或业务数据")),
            permission("ACCOUNT_MANAGE", "账号管理", "允许管理账号基础信息。",
                    List.of("创建账号", "编辑账号租户、角色和知识库范围", "启用、禁用账号", "轮换账号 tokenVersion"),
                    List.of("不能重置其他账号密码")),
            permission("ACCOUNT_PASSWORD_RESET", "密码重置", "允许管理员重置其他账号密码。",
                    List.of("重置其他账号密码"),
                    List.of("创建、禁用或编辑账号基础信息")),
            permission("ROLE_MANAGE", "角色管理", "允许管理角色和角色权限绑定。",
                    List.of("创建角色", "编辑角色名称、说明和权限", "禁用非 ADMIN 角色"),
                    List.of("不能直接修改账号密码或账号知识库范围")),
            permission("KB_READ", "知识库读取", "允许查看知识库和检索内容。",
                    List.of("查看知识库列表", "查看文档列表", "发起检索读取"),
                    List.of("不能创建、编辑或删除知识库与文档")),
            permission("KB_WRITE", "知识库写入", "允许创建和编辑知识库配置。",
                    List.of("创建知识库", "编辑知识库基础配置"),
                    List.of("不能删除知识库")),
            permission("KB_DELETE", "知识库删除", "高危权限，允许删除知识库。",
                    List.of("删除知识库及其关联清理任务"),
                    List.of("不能管理账号、角色或密码")),
            permission("DOCUMENT_UPLOAD", "文档上传", "允许上传文档并触发摄入。",
                    List.of("上传单个或批量文档", "触发文档摄入任务"),
                    List.of("不能删除文档")),
            permission("DOCUMENT_DELETE", "文档删除", "高危权限，允许删除文档。",
                    List.of("删除文档", "触发文档向量清理任务"),
                    List.of("不能上传新文档")),
            permission("CHAT_WRITE", "问答写入", "允许创建和追加问答会话。",
                    List.of("发起问答", "创建会话", "追加会话消息"),
                    List.of("不能删除历史会话")),
            permission("CHAT_DELETE", "会话删除", "允许删除问答会话。",
                    List.of("删除单个会话", "批量删除会话"),
                    List.of("不能创建或编辑知识库")),
            permission("MAINTENANCE", "系统维护", "允许执行维护型动作。",
                    List.of("重新索引知识库", "重试摄入任务", "重试向量清理任务"),
                    List.of("不能管理账号、角色或密码"))
    );

    private final RoleRepository roleRepository;
    private final UserAccountRepository userAccountRepository;

    @Transactional(readOnly = true)
    public List<RoleResponse> listRoles() {
        return roleRepository.findAll().stream()
                .map(this::response)
                .toList();
    }

    @Transactional
    public RoleResponse create(RoleRequest request) {
        if (request == null || request.getCode() == null || request.getCode().isBlank()) {
            throw new IllegalArgumentException("role code is required");
        }
        String code = normalizeCode(request.getCode());
        if (roleRepository.existsByCode(code)) {
            throw new IllegalArgumentException("role already exists: " + code);
        }
        Role role = Role.builder()
                .code(code)
                .name(hasText(request.getName()) ? request.getName().trim() : code)
                .description(blankToNull(request.getDescription()))
                .permissions(String.join(",", normalizePermissions(request.getPermissions(), code)))
                .systemBuiltin(false)
                .disabled(Boolean.TRUE.equals(request.getDisabled()))
                .build();
        return response(roleRepository.save(role));
    }

    @Transactional
    public RoleResponse update(UUID roleId, RoleRequest request) {
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new IllegalArgumentException("role not found: " + roleId));
        if (request != null) {
            if (hasText(request.getName())) {
                role.setName(request.getName().trim());
            }
            if (request.getDescription() != null) {
                role.setDescription(blankToNull(request.getDescription()));
            }
            if (request.getPermissions() != null) {
                role.setPermissions(String.join(",", normalizePermissions(request.getPermissions(), role.getCode())));
            }
            if (request.getDisabled() != null) {
                if ("ADMIN".equals(role.getCode()) && request.getDisabled()) {
                    throw new IllegalArgumentException("ADMIN role cannot be disabled");
                }
                role.setDisabled(request.getDisabled());
            }
        }
        return response(roleRepository.save(role));
    }

    @Transactional
    public RoleResponse disable(UUID roleId) {
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new IllegalArgumentException("role not found: " + roleId));
        if ("ADMIN".equals(role.getCode())) {
            throw new IllegalArgumentException("ADMIN role cannot be disabled");
        }
        role.setDisabled(true);
        return response(roleRepository.save(role));
    }

    @Transactional(readOnly = true)
    public Role requireActiveRole(String roleCode) {
        String code = normalizeCode(roleCode);
        Role role = roleRepository.findByCode(code)
                .orElseThrow(() -> new IllegalArgumentException("role not found: " + code));
        if (role.isDisabled()) {
            throw new IllegalArgumentException("role is disabled: " + code);
        }
        return role;
    }

    public static List<String> normalizePermissions(List<String> permissions, String roleCode) {
        if ("ADMIN".equals(normalizeCode(roleCode))) {
            return List.of("*");
        }
        if (permissions == null || permissions.isEmpty()) {
            return List.of("KB_READ", "DOCUMENT_UPLOAD", "CHAT_WRITE");
        }
        return permissions.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toUpperCase().replace('-', '_'))
                .distinct()
                .toList();
    }

    public static List<String> parsePermissions(String raw, String roleCode) {
        if ("ADMIN".equals(normalizeCode(roleCode))) {
            return List.of("*");
        }
        if (raw == null || raw.isBlank()) {
            return normalizePermissions(List.of(), roleCode);
        }
        return normalizePermissions(Arrays.stream(raw.split(",")).toList(), roleCode);
    }

    public static String normalizeCode(String code) {
        return code == null || code.isBlank() ? "ANALYST" : code.trim().toUpperCase().replace('-', '_');
    }

    private static PermissionMetadataResponse permission(
            String code,
            String name,
            String description,
            List<String> allows,
            List<String> denies
    ) {
        return PermissionMetadataResponse.builder()
                .code(code)
                .name(name)
                .description(description)
                .allows(allows)
                .denies(denies)
                .build();
    }

    private RoleResponse response(Role role) {
        return RoleResponse.builder()
                .id(role.getId())
                .code(role.getCode())
                .name(role.getName())
                .description(role.getDescription())
                .permissions(parsePermissions(role.getPermissions(), role.getCode()))
                .systemBuiltin(role.isSystemBuiltin())
                .disabled(role.isDisabled())
                .userCount(userAccountRepository.countByRoleCodeAndDisabledFalse(role.getCode()))
                .build();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
