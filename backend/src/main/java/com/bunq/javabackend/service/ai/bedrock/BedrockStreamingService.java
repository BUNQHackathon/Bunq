package com.bunq.javabackend.service.ai.bedrock;

import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class BedrockStreamingService {

    private static final int MAX_RETRIES = 3;
    private static final Duration BASE_BACKOFF = Duration.ofMillis(200);

    private final BedrockRuntimeAsyncClient bedrockRuntimeAsyncClient;
    private final ObjectMapper objectMapper;
    private final Semaphore streamPermits;

    public BedrockStreamingService(BedrockRuntimeAsyncClient bedrockRuntimeAsyncClient,
                                    ObjectMapper objectMapper,
                                    @Value("${bedrock.max-concurrent:30}") int maxConcurrent) {
        this.bedrockRuntimeAsyncClient = bedrockRuntimeAsyncClient;
        this.objectMapper = objectMapper;
        this.streamPermits = new Semaphore(Math.min(8, maxConcurrent));
    }

    public Flux<String> invokeModelWithResponseStream(String modelId, String requestJson) {
        InvokeModelWithResponseStreamRequest request = InvokeModelWithResponseStreamRequest.builder()
                .modelId(modelId)
                .contentType("application/json")
                .accept("application/json")
                .body(SdkBytes.fromUtf8String(requestJson))
                .build();

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

            CompletableFuture<Void> future = bedrockRuntimeAsyncClient.invokeModelWithResponseStream(request, handler)
                    .whenComplete((v, ex) -> {
                        if (ex != null) sink.tryEmitError(ex);
                    });

            return sink.asFlux()
                    .doFinally(signal -> future.cancel(true));
        })
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
                      "max_tokens": 16384,
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

        return Flux.<StreamingDelta>defer(() -> {
            // Fix 8: per-subscription flag; once deltas have been emitted, don't retry.
            AtomicBoolean emitted = new AtomicBoolean(false);
            Sinks.Many<StreamingDelta> sink = Sinks.many().unicast().onBackpressureBuffer();

            StringBuilder buffer = new StringBuilder();

            InvokeModelWithResponseStreamResponseHandler handler = InvokeModelWithResponseStreamResponseHandler.builder()
                    .subscriber(InvokeModelWithResponseStreamResponseHandler.Visitor.builder()
                            .onChunk((PayloadPart chunk) -> {
                                buffer.append(chunk.bytes().asUtf8String());
                                // Fix 4: advance past consumed bytes rather than clearing the whole buffer.
                                // objectMapper.readTree reads the first complete JSON object; we track how
                                // many characters were consumed by comparing buffer length before and after
                                // we strip the parsed prefix, using a streaming parser for byte-offset tracking.
                                while (!buffer.isEmpty()) {
                                    int consumed = tryParseOne(buffer, sink, emitted);
                                    if (consumed <= 0) break;
                                    buffer.delete(0, consumed);
                                }
                            })
                            .build())
                    .onComplete(sink::tryEmitComplete)
                    .onError(sink::tryEmitError)
                    .build();

            CompletableFuture<Void> future = bedrockRuntimeAsyncClient.invokeModelWithResponseStream(request, handler)
                    .whenComplete((v, ex) -> {
                        if (ex != null) sink.tryEmitError(ex);
                    });

            return sink.asFlux()
                    .doFinally(signal -> future.cancel(true));
        })
        // Fix 8: only retry when nothing has been emitted yet for this subscription attempt.
        .retryWhen(Retry.backoff(MAX_RETRIES, BASE_BACKOFF)
                .filter(t -> isThrottling(t))
                .doBeforeRetry(signal -> log.warn("Bedrock chat streaming throttled (model={}), retry {}/{}",
                        modelId, signal.totalRetries() + 1, MAX_RETRIES)))
        // Fix 7: semaphore acquired before each attempt, released on terminal signal.
        .transformDeferred(upstream -> {
            boolean acquired;
            try {
                acquired = streamPermits.tryAcquire();
            } catch (Exception e) {
                return Flux.error(e);
            }
            if (!acquired) {
                log.warn("Bedrock streaming semaphore full; rejecting stream request for model {}", modelId);
                return Flux.error(new IllegalStateException("Bedrock streaming concurrency limit reached"));
            }
            return upstream.doFinally(signal -> streamPermits.release());
        });
    }

    /**
     * Attempts to parse one complete JSON object from the start of {@code buffer}.
     * Returns the number of characters consumed (> 0) on success, or 0 if more data is needed,
     * or -1 on an unrecoverable parse error (buffer should be cleared by caller).
     * Package-private for unit testing (Fix 4).
     */
    int tryParseOne(StringBuilder buffer, Sinks.Many<StreamingDelta> sink, AtomicBoolean emitted) {
        String s = buffer.toString();
        try {
            // Use a streaming JsonParser to detect the end-offset of the first complete JSON value.
            tools.jackson.core.JsonParser parser = objectMapper.createParser(s);
            parser.nextToken();
            parser.skipChildren();
            int endOffset = (int) parser.currentLocation().getCharOffset();
            parser.close();
            if (endOffset <= 0) return 0;
            String fragment = s.substring(0, endOffset);
            JsonNode node = objectMapper.readTree(fragment);
            processStreamingNode(node, sink, emitted);
            return endOffset;
        } catch (StreamReadException e) {
            // Incomplete JSON — wait for more data.
            return 0;
        } catch (JacksonException e) {
            log.warn("Unrecoverable chunk parse error, discarding buffer: {}", e.getMessage());
            return -1;
        } catch (Exception e) {
            log.warn("Unexpected error parsing streaming chunk, discarding buffer: {}", e.getMessage());
            return -1;
        }
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

    private void processStreamingNode(JsonNode node, Sinks.Many<StreamingDelta> sink, AtomicBoolean emitted) {
        String type = node.path("type").asString("");
        if ("content_block_delta".equals(type)) {
            JsonNode delta = node.path("delta");
            if ("text_delta".equals(delta.path("type").asString(""))) {
                emitted.set(true);
                sink.tryEmitNext(new StreamingDelta(delta.path("text").asString(""), null, null, null, null));
            }
        } else if ("message_start".equals(type)) {
            // Fix 5: input/cache token counts are in message_start.message.usage
            JsonNode usage = node.path("message").path("usage");
            if (!usage.isMissingNode()) {
                int inputTokens = usage.path("input_tokens").asInt(0);
                int cacheRead = usage.path("cache_read_input_tokens").asInt(0);
                int cacheCreation = usage.path("cache_creation_input_tokens").asInt(0);
                log.info("Bedrock usage (message_start) — cache_creation={} cache_read={} input={}",
                        cacheCreation, cacheRead, inputTokens);
                sink.tryEmitNext(new StreamingDelta(null, inputTokens, null, cacheRead, cacheCreation));
            }
        } else if ("message_delta".equals(type)) {
            // Fix 6: detect stop_reason=max_tokens
            JsonNode delta = node.path("delta");
            if ("max_tokens".equals(delta.path("stop_reason").asString(""))) {
                log.warn("Bedrock streaming response truncated at max_tokens");
            }
            // message_delta only carries output_tokens
            JsonNode usage = node.path("usage");
            if (!usage.isMissingNode()) {
                int outputTokens = usage.path("output_tokens").asInt(0);
                log.info("Bedrock usage (message_delta) — output={}", outputTokens);
                sink.tryEmitNext(new StreamingDelta(null, null, outputTokens, null, null));
            }
        }
    }
}
