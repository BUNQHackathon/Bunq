package com.bunq.javabackend.service.observability;

import com.bunq.javabackend.model.enums.BedrockModel;
import com.bunq.javabackend.model.observability.SessionCost;
import com.bunq.javabackend.model.observability.StageCost;
import com.bunq.javabackend.repository.SessionCostRepository;
import com.bunq.javabackend.service.infra.sse.SseEmitterService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * B11: Per-session cost rollup for Bedrock calls.
 *
 * <p>Maintains in-memory accumulators (ConcurrentHashMap) and persists to DynamoDB
 * asynchronously after each call. Also emits a {@code cost.update} SSE event so the
 * frontend can display a live cost counter.
 *
 * <p>Pricing constants are sourced from Anthropic public pricing (2026-04).
 * TODO: review pricing before billing — sourced 2026-04, Anthropic public pricing.
 */
@Slf4j
@Service
public class SessionCostService {

    // -----------------------------------------------------------------------
    // Pricing table — USD per million tokens
    // TODO: review pricing before billing — sourced 2026-04, Anthropic public pricing
    // -----------------------------------------------------------------------
    private static final Map<BedrockModel, ModelPricing> PRICING = Map.of(
            BedrockModel.HAIKU,    new ModelPricing(1.00,  5.00,  1.25, 0.10),
            BedrockModel.SONNET,   new ModelPricing(3.00, 15.00,  3.75, 0.30),
            BedrockModel.OPUS,     new ModelPricing(15.00, 75.00, 18.75, 1.50),
            BedrockModel.NOVA_PRO, new ModelPricing(0.80,  3.20,  0.80, 0.20),
            BedrockModel.NOVA_LITE,new ModelPricing(0.06,  0.24,  0.00, 0.00)
    );

    private record ModelPricing(
            double inputPerM, double outputPerM,
            double cacheWritePerM, double cacheReadPerM) {}

    private static final double M = 1_000_000.0;

    // -----------------------------------------------------------------------
    // In-memory accumulators — keyed by sessionId
    // -----------------------------------------------------------------------
    private final ConcurrentHashMap<String, SessionCostAccumulator> accumulators =
            new ConcurrentHashMap<>();

    private final SessionCostRepository repository;
    private final SseEmitterService sseEmitterService;
    private final Executor persistExecutor;

    public SessionCostService(SessionCostRepository repository,
                              SseEmitterService sseEmitterService) {
        this.repository      = repository;
        this.sseEmitterService = sseEmitterService;
        // Dedicated single-thread executor: never competes with pipelineExecutor/stageWorkerExecutor.
        this.persistExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "session-cost-persist");
            t.setDaemon(true);
            return t;
        });
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Records one Bedrock call. Updates the in-memory accumulator, then asynchronously
     * persists to DynamoDB and emits an SSE event.
     */
    public void recordCall(String sessionId, String stage, BedrockModel model,
                           int inputTokens, int outputTokens,
                           int cacheCreationTokens, int cacheReadTokens) {
        long callCents = computeCents(model, inputTokens, outputTokens,
                cacheCreationTokens, cacheReadTokens);

        SessionCostAccumulator acc = accumulators.computeIfAbsent(
                sessionId, k -> new SessionCostAccumulator());
        // addAndSnapshot is synchronized on acc: update totals and capture a self-consistent snapshot
        // in the same critical section, preventing a parallel recordCall from producing a stale read.
        SessionCost snapshot = acc.addAndSnapshot(sessionId, stage,
                inputTokens, outputTokens, cacheCreationTokens, cacheReadTokens, callCents);

        // Async: persist + SSE on a dedicated single-thread executor so cost I/O
        // never saturates the stageWorkerExecutor/pipelineExecutor pools.
        CompletableFuture.runAsync(() -> {
            try {
                repository.save(snapshot);
                sseEmitterService.send(sessionId, "cost.update", buildSsePayload(stage, model, snapshot));
            } catch (Exception e) {
                log.warn("SessionCostService: async persist/SSE failed for session {}: {}",
                        sessionId, e.getMessage());
            }
        }, persistExecutor);
    }

    /**
     * Returns the current in-memory SessionCost for the session, or null if no calls
     * have been recorded yet.
     */
    public SessionCost get(String sessionId) {
        SessionCostAccumulator acc = accumulators.get(sessionId);
        if (acc == null) return null;
        return acc.snapshot(sessionId);
    }

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    /** Computes cost in integer cents (USD * 100) for one call. */
    static long computeCents(BedrockModel model, int inputTokens, int outputTokens,
                             int cacheCreationTokens, int cacheReadTokens) {
        ModelPricing p = PRICING.get(model);
        if (p == null) return 0L;
        double usd = (inputTokens        / M) * p.inputPerM()
                   + (outputTokens       / M) * p.outputPerM()
                   + (cacheCreationTokens / M) * p.cacheWritePerM()
                   + (cacheReadTokens    / M) * p.cacheReadPerM();
        return Math.round(usd * 100.0);
    }

    private static SessionCost buildSnapshot(String sessionId, SessionCostAccumulator acc) {
        // Callers must already hold acc's monitor (i.e. be inside a synchronized(acc) block
        // or call acc.addAndSnapshot / acc.snapshot which are themselves synchronized).
        return SessionCost.builder()
                .sessionId(sessionId)
                .totalInputTokens(acc.totalInput)
                .totalOutputTokens(acc.totalOutput)
                .totalCacheCreationTokens(acc.totalCacheCreation)
                .totalCacheReadTokens(acc.totalCacheRead)
                .totalUsdCents(acc.totalCents)
                .perStage(acc.snapshotPerStage())
                .updatedAt(Instant.now().toString())
                .build();
    }

    private Map<String, Object> buildSsePayload(String stage, BedrockModel model,
                                                 SessionCost snapshot) {
        long totalInput  = snapshot.getTotalInputTokens();
        long totalOutput = snapshot.getTotalOutputTokens();
        long cacheRead   = snapshot.getTotalCacheReadTokens();
        long cacheTotal  = totalInput + cacheRead + snapshot.getTotalCacheCreationTokens();
        double cacheHitRatio = cacheTotal > 0 ? (double) cacheRead / cacheTotal : 0.0;

        Map<String, Object> payload = new HashMap<>();
        payload.put("stage",           stage);
        payload.put("model",           model.name());
        payload.put("total_input",     totalInput);
        payload.put("total_output",    totalOutput);
        payload.put("cache_hit_ratio", cacheHitRatio);
        payload.put("total_usd",       snapshot.getTotalUsdCents() / 100.0);
        return payload;
    }

    // -----------------------------------------------------------------------
    // Inner accumulator — all mutations are synchronised on the instance
    // -----------------------------------------------------------------------

    static final class SessionCostAccumulator {
        volatile long totalInput;
        volatile long totalOutput;
        volatile long totalCacheCreation;
        volatile long totalCacheRead;
        volatile long totalCents;
        final ConcurrentHashMap<String, long[]> perStage = new ConcurrentHashMap<>();
        // long[]: [input, output, cacheCreation, cacheRead, cents]

        synchronized void add(String stage,
                              int input, int output, int cacheCreation, int cacheRead,
                              long cents) {
            totalInput         += input;
            totalOutput        += output;
            totalCacheCreation += cacheCreation;
            totalCacheRead     += cacheRead;
            totalCents         += cents;

            perStage.compute(stage, (k, v) -> {
                if (v == null) v = new long[5];
                v[0] += input;
                v[1] += output;
                v[2] += cacheCreation;
                v[3] += cacheRead;
                v[4] += cents;
                return v;
            });
        }

        /** Updates totals and returns a self-consistent snapshot — all in one synchronized call. */
        synchronized SessionCost addAndSnapshot(String sessionId, String stage,
                                                int input, int output, int cacheCreation, int cacheRead,
                                                long cents) {
            add(stage, input, output, cacheCreation, cacheRead, cents);
            return buildSnapshot(sessionId, this);
        }

        /** Returns a self-consistent point-in-time snapshot of the accumulator. */
        synchronized SessionCost snapshot(String sessionId) {
            return buildSnapshot(sessionId, this);
        }

        Map<String, StageCost> snapshotPerStage() {
            Map<String, StageCost> result = new HashMap<>();
            perStage.forEach((stage, v) -> result.put(stage, StageCost.builder()
                    .inputTokens(v[0])
                    .outputTokens(v[1])
                    .cacheCreationTokens(v[2])
                    .cacheReadTokens(v[3])
                    .usdCents(v[4])
                    .build()));
            return result;
        }
    }
}
