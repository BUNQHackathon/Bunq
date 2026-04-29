package com.bunq.javabackend.service.ai.kb;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;

/**
 * Reranker — calls Cohere rerank-v3.5 via Bedrock invokeModel to re-score a
 * candidate list and return the top-N items in relevance-score order.
 *
 * <p>Gated behind {@code rerank.enabled} (default {@code true}) so the deployer
 * can disable without redeploy if the model is unavailable in the configured region.
 *
 * <p>Uses its own small semaphore (10 permits) independent of the main Bedrock
 * semaphore — rerank calls are short and cheap and use a different model/code path.
 */
@Slf4j
@Service
public class Reranker {

    private static final String MODEL_ID = "cohere.rerank-v3-5:0";
    private static final int SEMAPHORE_PERMITS = 10;

    /** id + text to rank — callers construct, Reranker returns a re-ordered subset. */
    public record RankedItem(String id, String text) {}

    private final BedrockRuntimeClient bedrockRuntimeClient;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final Semaphore semaphore = new Semaphore(SEMAPHORE_PERMITS);

    public Reranker(
            BedrockRuntimeClient bedrockRuntimeClient,
            ObjectMapper objectMapper,
            @Value("${rerank.enabled:true}") boolean enabled) {
        this.bedrockRuntimeClient = bedrockRuntimeClient;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }

    /**
     * Re-rank {@code documents} against {@code query} and return the top-{@code topN}
     * items in descending relevance-score order.
     *
     * <p>If reranking is disabled or fails, returns the first {@code topN} items
     * from the input list unchanged (preserving the caller's ordering).
     */
    public List<RankedItem> rerank(String query, List<RankedItem> documents, int topN) {
        if (!enabled) {
            log.debug("Rerank disabled — returning first {} of {} candidates", topN, documents.size());
            return documents.stream().limit(topN).toList();
        }
        if (documents.isEmpty()) return List.of();

        try {
            semaphore.acquire();
            try {
                return doRerank(query, documents, topN);
            } finally {
                semaphore.release();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("Rerank interrupted — falling back to top-{} passthrough", topN);
            return documents.stream().limit(topN).toList();
        }
    }

    private List<RankedItem> doRerank(String query, List<RankedItem> documents, int topN) {
        try {
            // Build Cohere rerank request body
            ObjectNode body = objectMapper.createObjectNode();
            body.put("query", query);
            body.put("top_n", Math.min(topN, documents.size()));
            body.put("api_version", 2);

            ArrayNode docs = body.putArray("documents");
            for (RankedItem item : documents) {
                docs.add(item.text() != null ? item.text() : "");
            }

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
            JsonNode results = root.path("results");
            if (!results.isArray() || results.isEmpty()) {
                log.warn("Rerank response had no results — falling back to top-{} passthrough", topN);
                return documents.stream().limit(topN).toList();
            }

            // Map result indices back to RankedItems, sort by relevance_score desc
            record ScoredIndex(int index, double score) {}
            List<ScoredIndex> scored = new ArrayList<>();
            for (JsonNode r : results) {
                int index = r.path("index").asInt(-1);
                double score = r.path("relevance_score").asDouble(0.0);
                if (index >= 0 && index < documents.size()) {
                    scored.add(new ScoredIndex(index, score));
                }
            }
            scored.sort(Comparator.comparingDouble(ScoredIndex::score).reversed());

            List<RankedItem> reranked = scored.stream()
                    .limit(topN)
                    .map(s -> documents.get(s.index()))
                    .toList();

            double topScore = scored.isEmpty() ? 0.0 : scored.get(0).score();
            log.info("Rerank: {} -> {} (top score {})", documents.size(), reranked.size(),
                    String.format("%.4f", topScore));

            return reranked;

        } catch (Exception ex) {
            log.warn("Rerank call failed ({}): {} — falling back to top-{} passthrough",
                    MODEL_ID, ex.getMessage(), topN);
            return documents.stream().limit(topN).toList();
        }
    }
}
