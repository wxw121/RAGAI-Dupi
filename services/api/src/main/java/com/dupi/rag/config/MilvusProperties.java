package com.dupi.rag.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "dupi.milvus")
@Getter
@Setter
public class MilvusProperties {
    private String host;
    private int port;
    private String collection;
    private String profileCollection;

    public String getProfileCollection() {
        return profileCollection == null || profileCollection.isBlank()
                ? collection + "_profiles_v2"
                : profileCollection;
    }
}
