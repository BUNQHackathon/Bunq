package com.bunq.javabackend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class DynamoDbConfig {

    @Bean
    public ExecutorService asyncExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
