package com.bunq.javabackend.service.infra;

import com.bunq.javabackend.model.enums.RunStatus;
import com.bunq.javabackend.model.launch.JurisdictionRun;
import com.bunq.javabackend.repository.JurisdictionRunRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    private void sweep() {
        try {
            List<JurisdictionRun> all = repository.findAll();
            Instant threshold = Instant.now().minus(Duration.ofMinutes(30));
            int flipped = 0;

            for (JurisdictionRun run : all) {
                if (run.getStatus() != RunStatus.RUNNING) continue;
                if (run.getLastRunAt() == null) continue;
                try {
                    if (Instant.parse(run.getLastRunAt()).isAfter(threshold)) continue;
                } catch (Exception e) {
                    log.warn("stale-run-sweeper: unparseable lastRunAt for {}/{}: {}",
                            run.getLaunchId(), run.getJurisdictionCode(), run.getLastRunAt());
                    continue;
                }
                run.setStatus(RunStatus.FAILED);
                run.setFailedStage(STAGE_ABANDONED);
                run.setLastError("Pipeline run abandoned (likely process restart)");
                repository.save(run);
                log.info("stale-run-sweeper: flipped {}/{}", run.getLaunchId(), run.getJurisdictionCode());
                flipped++;
            }

            log.info("stale-run-sweeper: scanned={} flipped={}", all.size(), flipped);
        } catch (Exception e) {
            log.error("stale-run-sweeper: sweep failed", e);
        }
    }
}
