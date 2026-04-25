package com.bunq.javabackend.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig {

    private static final int POOL_SIZE = 16;

    @Bean("pipelineExecutor")
    public Executor pipelineExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(POOL_SIZE);
        executor.setMaxPoolSize(POOL_SIZE);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("pipeline-worker-");
        executor.initialize();
        log.info("pipelineExecutor initialised: corePoolSize={} maxPoolSize={} queueCapacity=200", POOL_SIZE, POOL_SIZE);
        return executor;
    }

    @Bean("stageWorkerExecutor")
    public Executor stageWorkerExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(POOL_SIZE);
        executor.setMaxPoolSize(POOL_SIZE);
        executor.setQueueCapacity(5000);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setThreadNamePrefix("stage-worker-");
        executor.initialize();
        log.info("stageWorkerExecutor initialised: corePoolSize={} maxPoolSize={} queueCapacity=5000", POOL_SIZE, POOL_SIZE);
        return executor;
    }
}
