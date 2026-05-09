package com.bunq.javabackend.config.health;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.bedrock.BedrockClient;
import software.amazon.awssdk.services.bedrock.model.ListFoundationModelsRequest;

@Slf4j
@Component("bedrock")
@RequiredArgsConstructor
public class BedrockHealthIndicator implements HealthIndicator {

    private final BedrockClient bedrockClient;

    @Override
    public Health health() {
        try {
            bedrockClient.listFoundationModels(ListFoundationModelsRequest.builder().build());
            return Health.up().build();
        } catch (Exception e) {
            log.warn("Bedrock health check failed: {}", e.getMessage());
            return Health.down().withDetail("error", e.getMessage()).build();
        }
    }
}
