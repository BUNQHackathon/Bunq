package com.bunq.javabackend.service.pipeline;

import com.bunq.javabackend.dto.request.PipelineStartRequestDTO;
import com.bunq.javabackend.dto.response.CounterpartyDTO;
import com.bunq.javabackend.dto.response.events.PipelineCompletedEvent;
import com.bunq.javabackend.dto.response.events.StageCompletedEvent;
import com.bunq.javabackend.dto.response.events.StageFailedEvent;
import com.bunq.javabackend.dto.response.events.StageStartedEvent;
import com.bunq.javabackend.exception.PipelineStageException;
import com.bunq.javabackend.model.launch.JurisdictionRun;
import com.bunq.javabackend.model.sanction.Counterparty;
import com.bunq.javabackend.model.enums.CounterpartyType;
import com.bunq.javabackend.model.session.Session;
import com.bunq.javabackend.model.enums.SessionState;
import com.bunq.javabackend.repository.JurisdictionRunRepository;
import com.bunq.javabackend.repository.SessionRepository;
import com.bunq.javabackend.service.SessionService;
import com.bunq.javabackend.service.pipeline.stage.ExtractControlsStage;
import com.bunq.javabackend.service.pipeline.stage.ExtractObligationsStage;
import com.bunq.javabackend.service.pipeline.stage.GapAnalyzeStage;
import com.bunq.javabackend.service.pipeline.stage.GroundCheckStage;
import com.bunq.javabackend.service.pipeline.stage.IngestStage;
import com.bunq.javabackend.service.pipeline.stage.MapObligationsControlsStage;
import com.bunq.javabackend.service.pipeline.stage.NarrateStage;
import com.bunq.javabackend.service.pipeline.stage.SanctionsScreenStage;
import com.bunq.javabackend.service.sse.SseEmitterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineOrchestrator {

    private final SessionService sessionService;
    private final SessionRepository sessionRepository;
    private final JurisdictionRunRepository jurisdictionRunRepository;
    private final SseEmitterService sseEmitterService;
    private final IngestStage ingestStage;
    private final ExtractObligationsStage extractObligationsStage;
    private final ExtractControlsStage extractControlsStage;
    private final SanctionsScreenStage sanctionsScreenStage;
    private final MapObligationsControlsStage mapObligationsControlsStage;
    private final GapAnalyzeStage gapAnalyzeStage;
    private final GroundCheckStage groundCheckStage;
    private final NarrateStage narrateStage;

    @Async("pipelineExecutor")
    public void start(String sessionId, PipelineStartRequestDTO request) {
        List<Counterparty> counterparties = mapCounterparties(request.getCounterparties());
        PipelineContext ctx = new PipelineContext(
                sessionId,
                request.getRegulation(),
                request.getPolicy(),
                counterparties,
                request.getBriefText(),
                sseEmitterService
        );
        sessionRepository.findById(sessionId)
                .map(Session::getJurisdictionCode)
                .ifPresent(ctx::setJurisdictionCode);

        String launchId = request.getLaunchId();
        String jurisdictionCode = request.getJurisdictionCode();

        try {
            // CREATED → UPLOADING
            sessionService.updateState(sessionId, SessionState.UPLOADING);

            // Stage 1: Ingest
            runStageWithCheckpoint(ctx, ingestStage);

            // UPLOADING → EXTRACTING
            sessionService.updateState(sessionId, SessionState.EXTRACTING);

            // Stages 2+3 in parallel
            CompletableFuture<Void> oblFuture = runStageAsyncWithCheckpoint(ctx, extractObligationsStage);
            CompletableFuture<Void> ctrlFuture = runStageAsyncWithCheckpoint(ctx, extractControlsStage);
            CompletableFuture.allOf(oblFuture, ctrlFuture).join();

            // EXTRACTING → MAPPING
            sessionService.updateState(sessionId, SessionState.MAPPING);

            // Stages 4+5 in parallel
            CompletableFuture<Void> sanctionsFuture = runStageAsyncWithCheckpoint(ctx, sanctionsScreenStage);
            CompletableFuture<Void> mapFuture = runStageAsyncWithCheckpoint(ctx, mapObligationsControlsStage);
            CompletableFuture.allOf(sanctionsFuture, mapFuture).join();

            // MAPPING → SCORING → SANCTIONS
            sessionService.updateState(sessionId, SessionState.SCORING);
            sessionService.updateState(sessionId, SessionState.SANCTIONS);

            // Stage 6: Gap analyze
            runStageWithCheckpoint(ctx, gapAnalyzeStage);

            // Stage 7: Ground check
            runStageWithCheckpoint(ctx, groundCheckStage);

            // Stage 8: Narrate
            runStageWithCheckpoint(ctx, narrateStage);

            // SANCTIONS → COMPLETE
            sessionService.updateState(sessionId, SessionState.COMPLETE);

            if (launchId != null && jurisdictionCode != null) {
                try {
                    jurisdictionRunRepository.findByLaunchIdAndCode(launchId, jurisdictionCode)
                            .ifPresent(run -> {
                                run.setStatus("COMPLETE");
                                run.setLastRunAt(Instant.now().toString());
                                if (ctx.getSummary() != null) {
                                    String overall = ctx.getSummary().getOverall();
                                    run.setVerdict(overall != null ? overall.toUpperCase() : null);
                                    run.setGapsCount(ctx.getSummary().getGapCount());
                                }
                                run.setSanctionsHits(ctx.getSanctionHits() != null ? ctx.getSanctionHits().size() : 0);
                                run.setProofPackS3Key(ctx.getReportUrl());
                                jurisdictionRunRepository.save(run);
                            });
                } catch (Exception e) {
                    log.error("Failed to update JurisdictionRun for launch={} code={}: {}",
                            launchId, jurisdictionCode, e.getMessage(), e);
                }
            }

            sseEmitterService.send(sessionId, PipelineCompletedEvent.builder()
                    .sessionId(sessionId)
                    .timestamp(Instant.now())
                    .summary(ctx.getSummary())
                    .reportUrl(ctx.getReportUrl())
                    .build());

            sseEmitterService.send(sessionId, "done",
                    java.util.Map.of("session_id", sessionId));

            sseEmitterService.complete(sessionId);

        } catch (Exception e) {
            log.error("Pipeline failed for session {}: {}", sessionId, e.getMessage(), e);
            Throwable root = e;
            while ((root instanceof java.util.concurrent.CompletionException
                    || root instanceof java.util.concurrent.ExecutionException)
                    && root.getCause() != null) {
                root = root.getCause();
            }
            PipelineStage failedStage = root instanceof PipelineStageException pse
                    ? pse.getStage()
                    : PipelineStage.INGEST;
            sseEmitterService.send(sessionId, StageFailedEvent.builder()
                    .sessionId(sessionId)
                    .timestamp(Instant.now())
                    .stage(failedStage)
                    .errorCode("PIPELINE_ERROR")
                    .message(e.getMessage())
                    .build());
            if (launchId != null && jurisdictionCode != null) {
                try {
                    String stageName = failedStage.name();
                    Throwable rootCause = e.getCause() != null ? e.getCause() : e;
                    String errorMsg = e.getClass().getSimpleName() + ": " + rootCause.getMessage();
                    String truncatedError = errorMsg.length() > 1000 ? errorMsg.substring(0, 1000) : errorMsg;
                    jurisdictionRunRepository.findByLaunchIdAndCode(launchId, jurisdictionCode)
                            .ifPresent(run -> {
                                run.setStatus("FAILED");
                                run.setLastRunAt(Instant.now().toString());
                                run.setFailedStage(stageName);
                                run.setLastError(truncatedError);
                                jurisdictionRunRepository.save(run);
                            });
                } catch (Exception ex) {
                    log.error("Failed to mark JurisdictionRun FAILED for launch={} code={}",
                            launchId, jurisdictionCode, ex);
                }
            }
            try { sessionService.updateState(sessionId, SessionState.FAILED); } catch (Exception ignored) {}
            sseEmitterService.complete(sessionId);
        }
    }

    private boolean isCheckpointed(String sessionId, PipelineStage stage) {
        return sessionRepository.findById(sessionId)
                .map(s -> s.getCompletedStages() != null && s.getCompletedStages().contains(stage.name()))
                .orElse(false);
    }

    private void markCheckpointed(String sessionId, PipelineStage stage) {
        sessionRepository.findById(sessionId).ifPresent(s -> {
            List<String> completed = s.getCompletedStages() != null
                    ? new ArrayList<>(s.getCompletedStages())
                    : new ArrayList<>();
            if (!completed.contains(stage.name())) {
                completed.add(stage.name());
            }
            s.setCompletedStages(completed);
            sessionRepository.save(s);
        });
    }

    private void runStageWithCheckpoint(PipelineContext ctx, Stage stage) {
        if (isCheckpointed(ctx.getSessionId(), stage.stage())) {
            log.info("Skipping checkpointed stage {} for session {}", stage.stage(), ctx.getSessionId());
            return;
        }
        runStage(ctx, stage);
        markCheckpointed(ctx.getSessionId(), stage.stage());
    }

    private CompletableFuture<Void> runStageAsyncWithCheckpoint(PipelineContext ctx, Stage stage) {
        if (isCheckpointed(ctx.getSessionId(), stage.stage())) {
            log.info("Skipping checkpointed stage {} for session {}", stage.stage(), ctx.getSessionId());
            return CompletableFuture.completedFuture(null);
        }
        return runStageAsync(ctx, stage)
                .thenRun(() -> markCheckpointed(ctx.getSessionId(), stage.stage()));
    }

    private void runStage(PipelineContext ctx, Stage stage) {
        long start = System.currentTimeMillis();
        emitStarted(ctx.getSessionId(), stage.stage());
        try {
            stage.execute(ctx).join();
            emitCompleted(ctx.getSessionId(), stage.stage(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            throw new PipelineStageException(stage.stage(),
                    "Stage " + stage.stage() + " failed: " + e.getMessage(), e);
        }
    }

    private CompletableFuture<Void> runStageAsync(PipelineContext ctx, Stage stage) {
        long start = System.currentTimeMillis();
        emitStarted(ctx.getSessionId(), stage.stage());
        return stage.execute(ctx)
                .thenRun(() -> emitCompleted(ctx.getSessionId(), stage.stage(), System.currentTimeMillis() - start))
                .handle((v, ex) -> {
                    if (ex == null) return null;
                    sseEmitterService.send(ctx.getSessionId(), StageFailedEvent.builder()
                            .sessionId(ctx.getSessionId())
                            .timestamp(Instant.now())
                            .stage(stage.stage())
                            .errorCode("STAGE_ERROR")
                            .message(ex.getMessage())
                            .build());
                    log.error("Async stage {} failed: {}", stage.stage(), ex.getMessage(), ex);
                    Throwable cause = ex instanceof java.util.concurrent.CompletionException && ex.getCause() != null ? ex.getCause() : ex;
                    throw new PipelineStageException(stage.stage(),
                            "Stage " + stage.stage() + " failed: " + cause.getMessage(), cause);
                });
    }

    private void emitStarted(String sessionId, PipelineStage stage) {
        sseEmitterService.send(sessionId, StageStartedEvent.builder()
                .sessionId(sessionId)
                .timestamp(Instant.now())
                .stage(stage)
                .ordinal(stage.getOrdinal())
                .totalStages(PipelineStage.totalStages())
                .build());
    }

    private void emitCompleted(String sessionId, PipelineStage stage, long durationMs) {
        sseEmitterService.send(sessionId, StageCompletedEvent.builder()
                .sessionId(sessionId)
                .timestamp(Instant.now())
                .stage(stage)
                .durationMs(durationMs)
                .itemsProduced(0)
                .build());
    }

    private List<Counterparty> mapCounterparties(List<CounterpartyDTO> dtos) {
        if (dtos == null) return List.of();
        return dtos.stream().map(dto -> {
            Counterparty cp = new Counterparty();
            cp.setName(dto.getName());
            cp.setCountry(dto.getCountry());
            if (dto.getType() != null) {
                try { cp.setType(CounterpartyType.valueOf(dto.getType().toLowerCase())); }
                catch (Exception ignored) {}
            }
            return cp;
        }).toList();
    }
}
