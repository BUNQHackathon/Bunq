package com.bunq.javabackend.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig {

    private static final int POOL_SIZE = 8;

    @Bean("pipelineExecutor")
    public Executor pipelineExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(POOL_SIZE);
        executor.setMaxPoolSize(POOL_SIZE);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("pipeline-worker-");
        executor.initialize();
        log.info("pipelineExecutor initialised: corePoolSize={} maxPoolSize={} queueCapacity=100", POOL_SIZE, POOL_SIZE);
        return executor;
    }
}
