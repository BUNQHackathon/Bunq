package com.bunq.javabackend.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties("chat")
@Getter
@Setter
public class ChatConfig {

    private int topKPerKb = 5;
    private int topNMerged = 10;
    private int historyLimit = 50;
}
