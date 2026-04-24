package com.bunq.javabackend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("pipelineExecutor")
    public Executor pipelineExecutor() {
        return Executors.newFixedThreadPool(8);
    }
}
