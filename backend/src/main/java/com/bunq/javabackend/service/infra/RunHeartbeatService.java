package com.bunq.javabackend.service.infra;

import com.bunq.javabackend.model.launch.JurisdictionRun;
import com.bunq.javabackend.repository.JurisdictionRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks pipeline runs that are actively executing in this JVM and periodically
 * writes a liveness heartbeat for each one. {@link StaleRunSweeper} uses this
 * heartbeat (falling back to the run's start timestamp when absent) to tell a
 * healthily-running long stage apart from a run orphaned by a process restart:
 * if the JVM restarts, this in-memory registry is empty, no heartbeats are
 * written, and orphaned RUNNING rows are still swept as before.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RunHeartbeatService {

    private final JurisdictionRunRepository repository;

    private final Set<RunKey> activeRuns = ConcurrentHashMap.newKeySet();

    public void register(String launchId, String jurisdictionCode) {
        activeRuns.add(new RunKey(launchId, jurisdictionCode));
    }

    public void deregister(String launchId, String jurisdictionCode) {
        activeRuns.remove(new RunKey(launchId, jurisdictionCode));
    }

    @Scheduled(fixedDelay = 60_000)
    void writeHeartbeats() {
        if (activeRuns.isEmpty()) return;
        String now = Instant.now().toString();
        for (RunKey key : activeRuns) {
            try {
                repository.findByLaunchIdAndCode(key.launchId(), key.jurisdictionCode())
                        .ifPresent(run -> {
                            run.setLastHeartbeatAt(now);
                            repository.save(run);
                        });
            } catch (Exception e) {
                log.warn("run-heartbeat: failed to write heartbeat for {}/{}: {}",
                        key.launchId(), key.jurisdictionCode(), e.getMessage());
            }
        }
    }

    private record RunKey(String launchId, String jurisdictionCode) {}
}
