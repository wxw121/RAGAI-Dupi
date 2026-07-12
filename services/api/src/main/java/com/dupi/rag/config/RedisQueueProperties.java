package com.dupi.rag.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "dupi.redis")
@Getter
@Setter
public class RedisQueueProperties {
    private String ingestQueue;
    private String cancelChannel;
    /**
     * 摄入队列高水位。大于 0 时，上传入口会在落对象存储前检查 Redis 队列长度，
     * 队列积压达到阈值就快速拒绝，避免继续制造无法及时消费的后台任务。
     */
    private int maxPendingJobs;
    /**
     * 摄入补偿最多自动重试次数。达到阈值后任务进入死信状态，等待人工检查或手动重试。
     */
    private int maxRecoveryAttempts = 3;
}
