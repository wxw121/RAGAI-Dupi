package com.dupi.rag.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "dupi.security")
public class ApiSecurityProperties {

    /**
     * 公开 API 的共享访问密钥。为空时保持本地开发兼容，不启用公开 API 鉴权。
     */
    private String apiKey = "";

    /**
     * 内部 API 的共享访问密钥。为空时保持本地开发兼容，不启用 internal 鉴权。
     */
    private String internalKey = "";

    /**
     * Bearer token 的 HMAC 签名密钥。配置账号后必须提供，避免 token 被伪造。
     */
    private String authSecret = "";

    /**
     * 登录 token 有效期，默认 8 小时，便于本地测试和日常运维使用。
     */
    private long tokenTtlSeconds = 28_800;

    /**
     * 单个用户名连续登录失败次数上限，超过后进入短暂锁定窗口，降低密码爆破风险。
     */
    private int loginMaxFailures = 5;

    /**
     * 登录失败锁定时间，单位秒。设置为 0 可关闭锁定窗口，便于本地调试。
     */
    private long loginLockoutSeconds = 300;

    /**
     * 内置账号列表，用于轻量 RBAC。为空时保持旧版本本地开放模式。
     */
    private List<UserAccount> users = new ArrayList<>();

    public boolean hasConfiguredUsers() {
        return users != null && !users.isEmpty();
    }

    @Getter
    @Setter
    public static class UserAccount {
        private String username = "";
        private String password = "";
        private String passwordHash = "";
        private String tenantId = TenantContext.DEFAULT_TENANT_ID;
        private String role = "USER";
        private String permissions = "";
        private String knowledgeBaseIds = "";
        private String tokenVersion = "1";
        private boolean disabled = false;
    }
}
