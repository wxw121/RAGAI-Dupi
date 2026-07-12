package com.dupi.rag.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "dupi.audit")
public class AuditProperties {

    /**
     * 审计日志保留天数。设置为 0 或负数时关闭自动清理，便于合规场景改由外部归档系统接管。
     */
    private int retentionDays = 180;

    /**
     * 告警统计窗口，单位分钟，用于发现短时间内失败审计事件异常升高。
     */
    private int alertWindowMinutes = 30;

    /**
     * 窗口内失败审计事件达到该阈值时返回 WARN 告警。设置为 0 或负数时关闭告警摘要。
     */
    private int alertFailedThreshold = 10;
}
