package com.bunq.javabackend.service.infra;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaleRunSweeperTest {

    private static final Instant NOW = Instant.now();
    private static final Instant THRESHOLD = NOW.minus(Duration.ofMinutes(30));

    @Test
    void freshHeartbeatWithVeryOldLastRunAt_isNotStale() {
        // The bug being fixed: a long-running healthy stage (e.g. ~25 min mapping) with an
        // old start timestamp must not be flipped as long as the heartbeat is fresh.
        Instant freshHeartbeat = NOW.minus(Duration.ofMinutes(1));
        Instant veryOldLastRunAt = NOW.minus(Duration.ofMinutes(60));
        assertFalse(StaleRunSweeper.isStale(freshHeartbeat, veryOldLastRunAt, THRESHOLD));
    }

    @Test
    void nullHeartbeatWithOldLastRunAt_isStale() {
        // Legacy rows written before the heartbeat existed keep the old behaviour.
        Instant oldLastRunAt = NOW.minus(Duration.ofMinutes(60));
        assertTrue(StaleRunSweeper.isStale(null, oldLastRunAt, THRESHOLD));
    }

    @Test
    void nullHeartbeatWithRecentLastRunAt_isNotStale() {
        Instant recentLastRunAt = NOW.minus(Duration.ofMinutes(5));
        assertFalse(StaleRunSweeper.isStale(null, recentLastRunAt, THRESHOLD));
    }

    @Test
    void oldHeartbeatWithRecentLastRunAt_isStale() {
        // Heartbeat wins when present, even if lastRunAt (unused once heartbeats start) looks recent.
        Instant oldHeartbeat = NOW.minus(Duration.ofMinutes(60));
        Instant recentLastRunAt = NOW.minus(Duration.ofMinutes(5));
        assertTrue(StaleRunSweeper.isStale(oldHeartbeat, recentLastRunAt, THRESHOLD));
    }

    @Test
    void bothNull_isNotStale() {
        assertFalse(StaleRunSweeper.isStale(null, null, THRESHOLD));
    }
}
