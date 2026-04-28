package com.bunq.javabackend.service.ai.bedrock;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelWithResponseStreamRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelWithResponseStreamResponseHandler;
import software.amazon.awssdk.services.bedrockruntime.model.PayloadPart;

@Slf4j
@Service
@RequiredArgsConstructor
public class BedrockStreamingService {

    private final BedrockRuntimeAsyncClient bedrockRuntimeAsyncClient;
    private final ObjectMapper objectMapper;

    public Flux<String> invokeModelWithResponseStream(String modelId, String requestJson) {
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();

        InvokeModelWithResponseStreamRequest request = InvokeModelWithResponseStreamRequest.builder()
                .modelId(modelId)
                .contentType("application/json")
                .accept("application/json")
                .body(SdkBytes.fromUtf8String(requestJson))
                .build();

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

        bedrockRuntimeAsyncClient.invokeModelWithResponseStream(request, handler)
                .exceptionally(ex -> {
                    log.error("Bedrock streaming error for model {}", modelId, ex);
                    sink.tryEmitError(ex);
                    return null;
                });

        return sink.asFlux();
    }

    public record StreamingDelta(String text, Integer inputTokens, Integer outputTokens,
                                  Integer cacheReadTokens, Integer cacheCreationTokens) {}

    public Flux<StreamingDelta> streamWithCachedSystem(String modelId, String cachedSystemPrompt, String userMessage) {
        Sinks.Many<StreamingDelta> sink = Sinks.many().unicast().onBackpressureBuffer();

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

        InvokeModelWithResponseStreamResponseHandler handler = InvokeModelWithResponseStreamResponseHandler.builder()
                .subscriber(InvokeModelWithResponseStreamResponseHandler.Visitor.builder()
                        .onChunk((PayloadPart chunk) -> {
                            try {
                                JsonNode node = objectMapper.readTree(chunk.bytes().asUtf8String());
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
                            } catch (Exception e) {
                                log.warn("Failed to parse streaming chunk: {}", e.getMessage());
                            }
                        })
                        .build())
                .onComplete(sink::tryEmitComplete)
                .onError(sink::tryEmitError)
                .build();

        bedrockRuntimeAsyncClient.invokeModelWithResponseStream(request, handler)
                .exceptionally(ex -> {
                    log.error("Bedrock chat streaming error for model {}", modelId, ex);
                    sink.tryEmitError(ex);
                    return null;
                });

        return sink.asFlux();
    }
}
