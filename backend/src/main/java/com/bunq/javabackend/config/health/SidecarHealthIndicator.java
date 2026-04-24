package com.bunq.javabackend.config.health;

import com.bunq.javabackend.client.SidecarClient;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("sidecar")
@RequiredArgsConstructor
public class SidecarHealthIndicator implements HealthIndicator {

    private final SidecarClient client;

    @Override
    public Health health() {
        try {
            client.health();
            return Health.up().build();
        } catch (Exception e) {
            return Health.down().withDetail("error", e.getMessage()).build();
        }
    }
}
