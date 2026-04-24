package com.bunq.javabackend.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties("aws.bedrock.kb")
@Getter
@Setter
public class KnowledgeBaseConfig {

    private String regulationsId;
    private String policiesId;
    private String controlsId;
}
