package com.dupi.rag.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "dupi.upload-rate-limit")
public class UploadRateLimitProperties {

    /**
     * 是否启用上传接口限流。默认开启，避免批量大文件把摄入链路压垮。
     */
    private boolean enabled = true;

    /**
     * 每个限流窗口内允许的上传请求数，按客户端 IP 与 API Key 组合隔离。
     */
    private int requests = 20;

    /**
     * 限流窗口秒数。
     */
    private long windowSeconds = 60;
}
