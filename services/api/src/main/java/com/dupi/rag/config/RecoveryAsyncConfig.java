package com.dupi.rag.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class RecoveryAsyncConfig {
    @Bean(name = "recoveryExecutor")
    public Executor recoveryExecutor(RecoveryProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getMaxConcurrentJobs());
        executor.setMaxPoolSize(properties.getMaxConcurrentJobs());
        executor.setQueueCapacity(properties.getMaxConcurrentJobs() * 2);
        executor.setThreadNamePrefix("recovery-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
