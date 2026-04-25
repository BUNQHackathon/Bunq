package com.bunq.javabackend.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;
import software.amazon.awssdk.services.bedrockruntime.model.ThrottlingException;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

@Slf4j
@Service
public class BedrockService {

    // Fallback chain: if a model is throttled, retry on the next model in the list.
    // Order is cheapest→most-capable so we only escalate under pressure.
    private static final Map<String, List<String>> FALLBACK_CHAIN = Map.of(
            "eu.anthropic.claude-haiku-4-5-20251001-v1:0", List.of(
                    "eu.anthropic.claude-sonnet-4-6",
                    "eu.anthropic.claude-opus-4-7"),
            "eu.anthropic.claude-sonnet-4-6", List.of(
                    "eu.anthropic.claude-opus-4-7"),
            "eu.anthropic.claude-opus-4-7", List.of()
    );

    private final BedrockRuntimeClient bedrockRuntimeClient;
    private final ObjectMapper objectMapper;
    private final Semaphore bedrockPermits;

    public BedrockService(BedrockRuntimeClient bedrockRuntimeClient,
                          ObjectMapper objectMapper,
                          @Value("${bedrock.max-concurrent:30}") int maxConcurrent) {
        this.bedrockRuntimeClient = bedrockRuntimeClient;
        this.objectMapper = objectMapper;
        this.bedrockPermits = new Semaphore(maxConcurrent);
    }

    public JsonNode invokeModel(String modelId, String requestJson) {
        List<String> candidates = new java.util.ArrayList<>();
        candidates.add(modelId);
        candidates.addAll(FALLBACK_CHAIN.getOrDefault(modelId, List.of()));

        ThrottlingException lastThrottle = null;
        for (int i = 0; i < candidates.size(); i++) {
            String currentModel = candidates.get(i);
            try {
                bedrockPermits.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted waiting for Bedrock permit", e);
            }
            try {
                InvokeModelRequest request = InvokeModelRequest.builder()
                        .modelId(currentModel)
                        .contentType("application/json")
                        .accept("application/json")
                        .body(SdkBytes.fromUtf8String(requestJson))
                        .build();

                InvokeModelResponse response = bedrockRuntimeClient.invokeModel(request);
                try {
                    JsonNode root = objectMapper.readTree(response.body().asUtf8String());
                    JsonNode usage = root.path("usage");
                    if (!usage.isMissingNode()) {
                        log.info("Bedrock usage — cache_creation={} cache_read={} input={} output={}",
                                usage.path("cache_creation_input_tokens").asInt(),
                                usage.path("cache_read_input_tokens").asInt(),
                                usage.path("input_tokens").asInt(),
                                usage.path("output_tokens").asInt());
                    }
                    return root;
                } catch (Exception e) {
                    throw new RuntimeException("Failed to parse Bedrock response", e);
                }
            } catch (ThrottlingException e) {
                lastThrottle = e;
                if (i + 1 < candidates.size()) {
                    log.warn("Model {} throttled after retries, falling back to {}", currentModel, candidates.get(i + 1));
                }
            } finally {
                bedrockPermits.release();
            }
        }
        throw lastThrottle;
    }

    /**
     * Invokes Bedrock with tool_use. Builds a messages-API request with a cached system prompt,
     * a single user message containing userInput serialised as JSON, and the tool definition.
     * Returns the tool_use block's input node from the response content array.
     * <p>
     * Expected response shape: {"content": [{"type": "tool_use", "input": {...}}], "usage": {...}}
     */
    public JsonNode invokeModelWithTool(String modelId, String systemPrompt, Object userInput, String toolJson) {
        try {
            String userText = objectMapper.writeValueAsString(userInput);

            // Build Anthropic messages-API request JSON manually to avoid SDK dependency on the full schema
            String requestJson = """
                    {
                      "anthropic_version": "bedrock-2023-05-31",
                      "max_tokens": 32768,
                      "system": [
                        {
                          "type": "text",
                          "text": %s,
                          "cache_control": {"type": "ephemeral"}
                        }
                      ],
                      "tools": [%s],
                      "tool_choice": {"type": "any"},
                      "messages": [
                        {
                          "role": "user",
                          "content": %s
                        }
                      ]
                    }
                    """.formatted(
                    objectMapper.writeValueAsString(systemPrompt),
                    toolJson,
                    objectMapper.writeValueAsString(userText));

            JsonNode root = invokeModel(modelId, requestJson);

            // Extract tool_use input block
            JsonNode content = root.path("content");
            if (content.isArray()) {
                for (JsonNode block : content) {
                    if ("tool_use".equals(block.path("type").asText())) {
                        JsonNode input = block.path("input");
                        // DIAG: log empty/suspect tool_use inputs to surface truncation
                        String stopReason = root.path("stop_reason").asText("?");
                        if (input.isMissingNode() || input.isEmpty()) {
                            log.warn("Bedrock tool_use input EMPTY (stop_reason={}, raw_block={})",
                                    stopReason, block.toString().substring(0, Math.min(500, block.toString().length())));
                        } else if ("max_tokens".equals(stopReason)) {
                            log.warn("Bedrock tool_use TRUNCATED at max_tokens (stop_reason={}, input_keys={}, sample={})",
                                    stopReason,
                                    input.propertyNames(),
                                    input.toString().substring(0, Math.min(800, input.toString().length())));
                        }
                        return input;
                    }
                }
                // No tool_use block found — log what came back instead
                log.warn("Bedrock returned no tool_use block (stop_reason={}, content_types={})",
                        root.path("stop_reason").asText("?"),
                        java.util.stream.StreamSupport.stream(content.spliterator(), false)
                                .map(b -> b.path("type").asText("?")).toList());
            }
            return root;
        } catch (Exception e) {
            log.error("Failed to invoke Bedrock with tool: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to invoke Bedrock with tool: " + e.getMessage(), e);
        }
    }
}
