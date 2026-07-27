package com.bunq.javabackend.service.infra;

import com.bunq.javabackend.model.enums.RunStatus;
import com.bunq.javabackend.model.launch.JurisdictionRun;
import com.bunq.javabackend.repository.JurisdictionRunRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class StaleRunSweeper {

    private static final String STAGE_ABANDONED = "ABANDONED";

    private final JurisdictionRunRepository repository;

    @PostConstruct
    public void init() {
        new Thread(this::sweep, "stale-run-sweeper").start();
    }

    @Scheduled(fixedDelay = 300_000)
    void sweep() {
        try {
            List<JurisdictionRun> all = repository.findAll();
            Instant threshold = Instant.now().minus(Duration.ofMinutes(30));
            int flipped = 0;

            for (JurisdictionRun run : all) {
                if (run.getStatus() != RunStatus.RUNNING) continue;
                Instant heartbeat = parseInstant(run.getLastHeartbeatAt(), "lastHeartbeatAt", run);
                Instant lastRunAt = parseInstant(run.getLastRunAt(), "lastRunAt", run);
                if (!isStale(heartbeat, lastRunAt, threshold)) continue;

                JurisdictionRun fresh = repository
                        .findByLaunchIdAndCode(run.getLaunchId(), run.getJurisdictionCode())
                        .orElse(null);
                if (fresh == null || fresh.getStatus() != RunStatus.RUNNING) continue;
                Instant freshHeartbeat = parseInstant(fresh.getLastHeartbeatAt(), "lastHeartbeatAt", fresh);
                Instant freshLastRunAt = parseInstant(fresh.getLastRunAt(), "lastRunAt", fresh);
                if (!isStale(freshHeartbeat, freshLastRunAt, threshold)) continue;

                fresh.setStatus(RunStatus.FAILED);
                fresh.setFailedStage(STAGE_ABANDONED);
                fresh.setLastError("Pipeline run abandoned (likely process restart)");
                repository.save(fresh);

                Instant reference = freshHeartbeat != null ? freshHeartbeat : freshLastRunAt;
                String source = freshHeartbeat != null ? "heartbeat" : "lastRunAt";
                long minutes = Duration.between(reference, Instant.now()).toMinutes();
                log.info("stale-run-sweeper: flipped {}/{} (no {} for {} minutes)",
                        fresh.getLaunchId(), fresh.getJurisdictionCode(), source, minutes);
                flipped++;
            }

            log.info("stale-run-sweeper: scanned={} flipped={}", all.size(), flipped);
        } catch (Exception e) {
            log.error("stale-run-sweeper: sweep failed", e);
        }
    }

    private Instant parseInstant(String value, String fieldName, JurisdictionRun run) {
        if (value == null) return null;
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            log.warn("stale-run-sweeper: unparseable {} for {}/{}: {}",
                    fieldName, run.getLaunchId(), run.getJurisdictionCode(), value);
            return null;
        }
    }

    /**
     * A run is stale when its most authoritative liveness signal is older than the threshold.
     * lastHeartbeatAt (refreshed every minute by RunHeartbeatService while the run is active in
     * this JVM) takes priority when present; lastRunAt (a start timestamp, never refreshed) is
     * only used as a fallback for rows written before the heartbeat existed. Missing both never
     * flips a run.
     */
    static boolean isStale(Instant lastHeartbeatAt, Instant lastRunAt, Instant threshold) {
        Instant reference = lastHeartbeatAt != null ? lastHeartbeatAt : lastRunAt;
        if (reference == null) return false;
        return reference.isBefore(threshold);
    }
}
