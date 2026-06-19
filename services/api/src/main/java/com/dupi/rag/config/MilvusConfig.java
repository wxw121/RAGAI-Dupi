package com.dupi.rag.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MilvusConfig {

    private MilvusServiceClient client;

    @Bean
    public MilvusServiceClient milvusClient(MilvusProperties properties) {
        client = new MilvusServiceClient(
                ConnectParam.newBuilder()
                        .withHost(properties.getHost())
                        .withPort(properties.getPort())
                        .build()
        );
        return client;
    }

    @PreDestroy
    public void close() {
        if (client != null) {
            client.close();
        }
    }
}
