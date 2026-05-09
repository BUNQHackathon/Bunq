package com.bunq.javabackend.service.ai.bedrock;

import tools.jackson.core.exc.StreamReadException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.util.retry.Retry;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelWithResponseStreamRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelWithResponseStreamResponseHandler;
import software.amazon.awssdk.services.bedrockruntime.model.PayloadPart;
import software.amazon.awssdk.services.bedrockruntime.model.ThrottlingException;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class BedrockStreamingService {

    private static final int MAX_RETRIES = 3;
    private static final Duration BASE_BACKOFF = Duration.ofMillis(200);

    private final BedrockRuntimeAsyncClient bedrockRuntimeAsyncClient;
    private final ObjectMapper objectMapper;

    public Flux<String> invokeModelWithResponseStream(String modelId, String requestJson) {
        // Request is immutable and safe to build once outside the defer.
        InvokeModelWithResponseStreamRequest request = InvokeModelWithResponseStreamRequest.builder()
                .modelId(modelId)
                .contentType("application/json")
                .accept("application/json")
                .body(SdkBytes.fromUtf8String(requestJson))
                .build();

        // Flux.defer ensures each subscription (including every retry attempt) creates a fresh
        // sink + handler pair. Without this, a throttling error would terminate the original sink
        // before the retry re-invokes the SDK, leaving retries writing into a dead sink.
        return Flux.<String>defer(() -> {
            Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();

            InvokeModelWithResponseStreamResponseHandler handler = InvokeModelWithResponseStreamResponseHandler.builder()
                    .subscriber(InvokeModelWithResponseStreamResponseHandler.Visitor.builder()
                            .onChunk((PayloadPart chunk) -> {
                                String text = chunk.bytes().asUtf8String();
                                sink.tryEmitNext(text);
                            })
                            .build())
                    .onComplete(sink::tryEmitComplete)
                    .onError(sink::tryEmitError)
                    .build();

            // Fire the SDK call as a side-effect — it returns immediately; chunks arrive via handler callbacks.
            // Backstop: if a handshake-level failure completes the future before handler.onError fires,
            // ensure the sink terminates so subscribers don't hang.
            CompletableFuture<Void> future = bedrockRuntimeAsyncClient.invokeModelWithResponseStream(request, handler)
                    .whenComplete((v, ex) -> {
                        if (ex != null) sink.tryEmitError(ex);
                    });

            // Return the live sink immediately so the caller receives chunks as they arrive,
            // not batched after the entire stream completes.
            return sink.asFlux()
                    .doFinally(signal -> future.cancel(true));
        })
        // NOTE: retryWhen is outside defer so that on each retry the full defer block re-runs,
        // creating a fresh sink + handler + SDK call. However, if a mid-stream error triggers a
        // retry, events already delivered in the failed attempt will be re-sent in the new attempt.
        // This is inherent to stateless streaming retry; callers must tolerate duplicate chunks.
        .retryWhen(Retry.backoff(MAX_RETRIES, BASE_BACKOFF)
                .filter(this::isThrottling)
                .doBeforeRetry(signal -> log.warn("Bedrock streaming throttled (model={}), retry {}/{}",
                        modelId, signal.totalRetries() + 1, MAX_RETRIES)));
    }

    public record StreamingDelta(String text, Integer inputTokens, Integer outputTokens,
                                  Integer cacheReadTokens, Integer cacheCreationTokens) {}

    public Flux<StreamingDelta> streamWithCachedSystem(String modelId, String cachedSystemPrompt, String userMessage) {
        String requestJson;
        try {
            requestJson = """
                    {
                      "anthropic_version": "bedrock-2023-05-31",
                      "max_tokens": 4096,
                      "system": [
                        {
                          "type": "text",
                          "text": %s,
                          "cache_control": {"type": "ephemeral"}
                        }
                      ],
                      "messages": [
                        {
                          "role": "user",
                          "content": %s
                        }
                      ]
                    }
                    """.formatted(
                    objectMapper.writeValueAsString(cachedSystemPrompt),
                    objectMapper.writeValueAsString(userMessage));
        } catch (Exception e) {
            return Flux.error(new RuntimeException("Failed to build Bedrock streaming request", e));
        }

        InvokeModelWithResponseStreamRequest request = InvokeModelWithResponseStreamRequest.builder()
                .modelId(modelId)
                .contentType("application/json")
                .accept("application/json")
                .body(SdkBytes.fromUtf8String(requestJson))
                .build();

        // Flux.defer: each retry attempt gets a fresh sink and a fresh buffer — partial state from
        // a failed attempt cannot bleed into the next one.
        return Flux.<StreamingDelta>defer(() -> {
            Sinks.Many<StreamingDelta> sink = Sinks.many().unicast().onBackpressureBuffer();

            // Per-attempt buffer: accumulates UTF-8 bytes across PayloadParts until a complete JSON object is received.
            StringBuilder buffer = new StringBuilder();

            InvokeModelWithResponseStreamResponseHandler handler = InvokeModelWithResponseStreamResponseHandler.builder()
                    .subscriber(InvokeModelWithResponseStreamResponseHandler.Visitor.builder()
                            .onChunk((PayloadPart chunk) -> {
                                buffer.append(chunk.bytes().asUtf8String());
                                // Attempt to parse from the start of the buffer.
                                // If the buffer holds a complete JSON object, readTree succeeds; otherwise
                                // StreamReadException is thrown (incomplete input) and we keep buffering.
                                // Any other exception means corruption — log and reset so subsequent events can still be parsed.
                                while (!buffer.isEmpty()) {
                                    try {
                                        JsonNode node = objectMapper.readTree(buffer.toString());
                                        // Successful parse — consume the buffer and process the event.
                                        buffer.setLength(0);
                                        processStreamingNode(node, sink);
                                    } catch (StreamReadException e) {
                                        // Incomplete JSON — wait for more data.
                                        break;
                                    } catch (Exception e) {
                                        log.warn("Unrecoverable chunk parse error, discarding buffer: {}", e.getMessage());
                                        buffer.setLength(0);
                                        break;
                                    }
                                }
                            })
                            .build())
                    .onComplete(sink::tryEmitComplete)
                    .onError(sink::tryEmitError)
                    .build();

            // Fire the SDK call as a side-effect — it returns immediately; chunks arrive via handler callbacks.
            // Backstop: if a handshake-level failure completes the future before handler.onError fires,
            // ensure the sink terminates so subscribers don't hang.
            CompletableFuture<Void> future = bedrockRuntimeAsyncClient.invokeModelWithResponseStream(request, handler)
                    .whenComplete((v, ex) -> {
                        if (ex != null) sink.tryEmitError(ex);
                    });

            // Return the live sink immediately so the caller receives chunks as they arrive,
            // not batched after the entire stream completes.
            return sink.asFlux()
                    .doFinally(signal -> future.cancel(true));
        })
        // NOTE: retryWhen is outside defer so that on each retry the full defer block re-runs,
        // creating a fresh sink + handler + SDK call. However, if a mid-stream error triggers a
        // retry, events already delivered in the failed attempt will be re-sent in the new attempt.
        // This is inherent to stateless streaming retry; callers must tolerate duplicate chunks.
        .retryWhen(Retry.backoff(MAX_RETRIES, BASE_BACKOFF)
                .filter(this::isThrottling)
                .doBeforeRetry(signal -> log.warn("Bedrock chat streaming throttled (model={}), retry {}/{}",
                        modelId, signal.totalRetries() + 1, MAX_RETRIES)));
    }

    private boolean isThrottling(Throwable t) {
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth++ < 10) {
            if (cur instanceof ThrottlingException) return true;
            cur = cur.getCause();
        }
        return false;
    }

    private void processStreamingNode(JsonNode node, Sinks.Many<StreamingDelta> sink) {
        String type = node.path("type").asText("");
        if ("content_block_delta".equals(type)) {
            JsonNode delta = node.path("delta");
            if ("text_delta".equals(delta.path("type").asText(""))) {
                sink.tryEmitNext(new StreamingDelta(delta.path("text").asText(), null, null, null, null));
            }
        } else if ("message_delta".equals(type)) {
            JsonNode usage = node.path("usage");
            if (!usage.isMissingNode()) {
                int inputTokens = usage.path("input_tokens").asInt(0);
                int outputTokens = usage.path("output_tokens").asInt(0);
                int cacheRead = usage.path("cache_read_input_tokens").asInt(0);
                int cacheCreation = usage.path("cache_creation_input_tokens").asInt(0);
                log.info("Bedrock usage — cache_creation={} cache_read={} input={} output={}",
                        cacheCreation, cacheRead, inputTokens, outputTokens);
                sink.tryEmitNext(new StreamingDelta(null, inputTokens, outputTokens, cacheRead, cacheCreation));
            }
        }
    }
}
