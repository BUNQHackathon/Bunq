package com.bunq.javabackend.config.health;

import com.bunq.javabackend.client.SidecarClient;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("sidecar")
@RequiredArgsConstructor
public class SidecarHealthIndicator implements HealthIndicator {

    private static final long CACHE_TTL_MS = 30_000L;

    private final SidecarClient client;

    private volatile Health cachedHealth = Health.unknown().build();
    private volatile long lastProbeMs = 0L;

    @Override
    public Health health() {
        long now = System.currentTimeMillis();
        if (now - lastProbeMs < CACHE_TTL_MS) {
            return cachedHealth;
        }
        try {
            client.health();
            cachedHealth = Health.up().build();
        } catch (Exception e) {
            cachedHealth = Health.down().withDetail("error", e.getMessage()).build();
        }
        lastProbeMs = System.currentTimeMillis();
        return cachedHealth;
    }
}
