package com.dupi.rag.config;

public final class TenantContext {

    public static final String DEFAULT_TENANT_ID = "default";

    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static String getTenantId() {
        String tenantId = CURRENT_TENANT.get();
        return tenantId == null || tenantId.isBlank() ? DEFAULT_TENANT_ID : tenantId;
    }

    public static boolean hasTenantId() {
        String tenantId = CURRENT_TENANT.get();
        return tenantId != null && !tenantId.isBlank();
    }

    public static void setTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            CURRENT_TENANT.set(DEFAULT_TENANT_ID);
            return;
        }
        CURRENT_TENANT.set(tenantId);
    }

    public static void clear() {
        CURRENT_TENANT.remove();
    }
}
