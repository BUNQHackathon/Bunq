package com.bunq.javabackend.config.health;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

@Component("bedrock")
@RequiredArgsConstructor
public class BedrockHealthIndicator implements HealthIndicator {

    private final BedrockRuntimeClient bedrockRuntimeClient;

    @Override
    public Health health() {
        if (bedrockRuntimeClient != null) {
            return Health.up().build();
        }
        return Health.down().withDetail("error", "BedrockRuntimeClient bean is null").build();
    }
}
