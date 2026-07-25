package com.bunq.javabackend.service.ai.bedrock;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * TitanEmbeddingService — calls amazon.titan-embed-text-v2:0 via Bedrock invokeModel
 * to produce float[] embeddings for text inputs.
 *
 * <p>Gated behind {@code embedding.enabled} (default {@code true}). When disabled
 * or on error, returns null (fail-safe passthrough, mirrors Reranker pattern).
 *
 * <p>Uses its own semaphore (20 permits) for concurrency control.
 */
@Slf4j
@Service
public class TitanEmbeddingService {

    private static final String MODEL_ID = "amazon.titan-embed-text-v2:0";
    private static final int SEMAPHORE_PERMITS = 20;

    private final BedrockRuntimeClient bedrockRuntimeClient;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final Semaphore semaphore = new Semaphore(SEMAPHORE_PERMITS);
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public TitanEmbeddingService(
            BedrockRuntimeClient bedrockRuntimeClient,
            ObjectMapper objectMapper,
            @Value("${embedding.enabled:true}") boolean enabled) {
        this.bedrockRuntimeClient = bedrockRuntimeClient;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }

    /**
     * Embed a single text string via Titan v2.
     *
     * @return float[] embedding, or null if disabled or on error (fail-safe)
     */
    public float[] embed(String text) {
        if (!enabled) {
            log.debug("Embedding disabled — returning null");
            return null;
        }
        if (text == null || text.isBlank()) {
            log.warn("embed() called with blank text — returning null");
            return null;
        }

        try {
            if (!semaphore.tryAcquire(10, TimeUnit.SECONDS)) {
                log.warn("Embedding semaphore timeout for text length {} — returning null", text.length());
                return null;
            }
            try {
                return doEmbed(text);
            } finally {
                semaphore.release();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("Embedding interrupted — returning null");
            return null;
        }
    }

    /**
     * Embed a batch of id→text pairs in parallel (bounded by semaphore).
     * Entries whose embedding is null (disabled/error) are skipped in the result.
     *
     * @return map of id → float[] for successfully embedded inputs
     */
    public Map<String, float[]> embedBatch(Map<String, String> idToText) {
        if (!enabled) {
            log.debug("Embedding disabled — returning empty batch");
            return Map.of();
        }

        List<CompletableFuture<Map.Entry<String, float[]>>> futures = new ArrayList<>(idToText.size());

        for (Map.Entry<String, String> entry : idToText.entrySet()) {
            String id = entry.getKey();
            String text = entry.getValue();
            CompletableFuture<Map.Entry<String, float[]>> future = CompletableFuture.supplyAsync(() -> {
                float[] vec = embed(text);
                return vec != null ? Map.entry(id, vec) : null;
            }, executor);
            futures.add(future);
        }

        Map<String, float[]> result = new HashMap<>(idToText.size());
        for (CompletableFuture<Map.Entry<String, float[]>> f : futures) {
            try {
                Map.Entry<String, float[]> e = f.join();
                if (e != null) {
                    result.put(e.getKey(), e.getValue());
                }
            } catch (Exception ex) {
                log.warn("embedBatch: future failed — {}", ex.getMessage());
            }
        }

        log.info("embedBatch: embedded {}/{} items", result.size(), idToText.size());
        return result;
    }

    /**
     * Cosine similarity between two float vectors.
     * Returns 0.0 if either vector is null or zero-magnitude.
     */
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) return 0.0;
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot   += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) return 0.0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    // -------------------------------------------------------------------------
    // Private
    // -------------------------------------------------------------------------

    private float[] doEmbed(String text) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("inputText", text);
            String requestBody = objectMapper.writeValueAsString(body);

            InvokeModelRequest request = InvokeModelRequest.builder()
                    .modelId(MODEL_ID)
                    .contentType("application/json")
                    .accept("application/json")
                    .body(SdkBytes.fromUtf8String(requestBody))
                    .build();

            InvokeModelResponse response = bedrockRuntimeClient.invokeModel(request);
            String responseBody = response.body().asUtf8String();

            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode embeddingNode = root.path("embedding");
            if (!embeddingNode.isArray() || embeddingNode.isEmpty()) {
                log.warn("Titan embed response missing 'embedding' array — returning null");
                return null;
            }

            float[] vec = new float[embeddingNode.size()];
            for (int i = 0; i < vec.length; i++) {
                vec[i] = (float) embeddingNode.get(i).asDouble();
            }
            return vec;

        } catch (Exception ex) {
            log.warn("Titan embed call failed ({}): {} — returning null", MODEL_ID, ex.getMessage());
            return null;
        }
    }
}
