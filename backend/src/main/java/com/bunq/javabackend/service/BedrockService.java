package com.bunq.javabackend.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.SpecificToolChoice;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ThrottlingException;
import software.amazon.awssdk.services.bedrockruntime.model.Tool;
import software.amazon.awssdk.services.bedrockruntime.model.ToolChoice;
import software.amazon.awssdk.services.bedrockruntime.model.ToolConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.ToolInputSchema;
import software.amazon.awssdk.services.bedrockruntime.model.ToolSpecification;
import software.amazon.awssdk.services.bedrockruntime.model.ToolUseBlock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
                    "eu.amazon.nova-pro-v1:0",
                    "eu.amazon.nova-lite-v1:0"),
            "eu.anthropic.claude-sonnet-4-6", List.of(
                    "eu.amazon.nova-pro-v1:0",
                    "eu.amazon.nova-lite-v1:0"),
            "eu.amazon.nova-pro-v1:0", List.of(
                    "eu.amazon.nova-lite-v1:0"),
            "eu.amazon.nova-lite-v1:0", List.of()
    );

    private static boolean isNovaModel(String modelId) {
        return modelId != null && (modelId.startsWith("eu.amazon.nova") || modelId.startsWith("global.amazon.nova") || modelId.startsWith("amazon.nova"));
    }

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
                if (isNovaModel(currentModel)) {
                    if (i > 0) {
                        log.warn("Falling back from {} to Nova model {} via Converse API", candidates.get(i - 1), currentModel);
                    }
                    return invokeNovaViaConverse(currentModel, requestJson);
                }
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
     * Invokes a Nova model via Bedrock Converse API (unified format).
     * Translates an Anthropic-shaped request JSON into ConverseRequest,
     * and returns an Anthropic-shaped JsonNode {content:[{type:"text",text:"..."}]}.
     */
    private JsonNode invokeNovaViaConverse(String modelId, String requestJson) {
        try {
            JsonNode req = objectMapper.readTree(requestJson);

            // Build system prompt list
            List<SystemContentBlock> systemBlocks = new java.util.ArrayList<>();
            JsonNode systemNode = req.path("system");
            if (systemNode.isArray()) {
                for (JsonNode s : systemNode) {
                    systemBlocks.add(SystemContentBlock.builder().text(s.path("text").asText()).build());
                }
            } else if (systemNode.isTextual()) {
                systemBlocks.add(SystemContentBlock.builder().text(systemNode.asText()).build());
            }

            // Build messages
            List<Message> messages = new java.util.ArrayList<>();
            for (JsonNode msg : req.path("messages")) {
                String role = msg.path("role").asText("user");
                ConversationRole convRole = "assistant".equals(role) ? ConversationRole.ASSISTANT : ConversationRole.USER;
                JsonNode contentNode = msg.path("content");
                String text = contentNode.isTextual() ? contentNode.asText() : contentNode.toString();
                messages.add(Message.builder()
                        .role(convRole)
                        .content(ContentBlock.fromText(text))
                        .build());
            }

            ConverseRequest.Builder converseBuilder = ConverseRequest.builder()
                    .modelId(modelId)
                    .messages(messages);
            if (!systemBlocks.isEmpty()) {
                converseBuilder.system(systemBlocks);
            }

            ConverseResponse converseResponse = bedrockRuntimeClient.converse(converseBuilder.build());

            // Translate response to Anthropic-shaped JsonNode
            String responseText = converseResponse.output().message().content().stream()
                    .filter(b -> b.text() != null)
                    .map(ContentBlock::text)
                    .findFirst()
                    .orElse("");

            ObjectNode contentBlock = objectMapper.createObjectNode();
            contentBlock.put("type", "text");
            contentBlock.put("text", responseText);
            ObjectNode root = objectMapper.createObjectNode();
            root.putArray("content").add(contentBlock);
            return root;
        } catch (ThrottlingException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke Nova model via Converse API: " + e.getMessage(), e);
        }
    }

    /**
     * Invokes a Nova model via Bedrock Converse API with tool use.
     * Translates the Anthropic tool definition JSON into ConverseRequest toolConfig.
     * Returns the tool input JsonNode (same shape as Anthropic tool_use input).
     */
    private JsonNode invokeNovaWithToolViaConverse(String modelId, String systemPrompt, String userText, String toolJson) {
        try {
            JsonNode toolDef = objectMapper.readTree(toolJson);
            String toolName = toolDef.path("name").asText();
            String toolDescription = toolDef.path("description").asText();
            JsonNode inputSchema = toolDef.path("input_schema");

            ToolInputSchema toolInputSchema = ToolInputSchema.fromJson(jsonNodeToDocument(inputSchema));
            ToolSpecification toolSpec = ToolSpecification.builder()
                    .name(toolName)
                    .description(toolDescription)
                    .inputSchema(toolInputSchema)
                    .build();
            ToolConfiguration toolConfig = ToolConfiguration.builder()
                    .tools(Tool.builder().toolSpec(toolSpec).build())
                    .toolChoice(ToolChoice.fromTool(
                            SpecificToolChoice.builder().name(toolName).build()))
                    .build();

            Message userMessage = Message.builder()
                    .role(ConversationRole.USER)
                    .content(ContentBlock.fromText(userText))
                    .build();

            ConverseRequest converseRequest = ConverseRequest.builder()
                    .modelId(modelId)
                    .system(SystemContentBlock.builder().text(systemPrompt).build())
                    .messages(userMessage)
                    .toolConfig(toolConfig)
                    .build();

            ConverseResponse converseResponse = bedrockRuntimeClient.converse(converseRequest);

            // Extract tool use input from response
            for (ContentBlock block : converseResponse.output().message().content()) {
                if (block.toolUse() != null) {
                    ToolUseBlock toolUse = block.toolUse();
                    return objectMapper.readTree(documentToJson(toolUse.input()));
                }
            }
            log.warn("Nova Converse returned no tool_use block for model {}", modelId);
            return objectMapper.createObjectNode();
        } catch (ThrottlingException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke Nova model with tool via Converse API: " + e.getMessage(), e);
        }
    }

    /** Converts a Jackson JsonNode into an AWS SDK Document (recursive). */
    private static Document jsonNodeToDocument(JsonNode node) {
        if (node == null || node.isNull()) return Document.fromNull();
        if (node.isBoolean()) return Document.fromBoolean(node.asBoolean());
        if (node.isNumber()) return Document.fromNumber(node.asText());
        if (node.isTextual()) return Document.fromString(node.asText());
        if (node.isArray()) {
            List<Document> list = new ArrayList<>();
            for (JsonNode item : node) list.add(jsonNodeToDocument(item));
            return Document.fromList(list);
        }
        if (node.isObject()) {
            Map<String, Document> map = new LinkedHashMap<>();
            node.properties().forEach(e -> map.put(e.getKey(), jsonNodeToDocument(e.getValue())));
            return Document.fromMap(map);
        }
        return Document.fromString(node.asText());
    }

    /** Converts an AWS SDK Document to a JSON string via Jackson. */
    private String documentToJson(Document doc) throws Exception {
        if (doc == null || doc.isNull()) return "null";
        if (doc.isBoolean()) return String.valueOf(doc.asBoolean());
        if (doc.isNumber()) return doc.asNumber().stringValue();
        if (doc.isString()) return objectMapper.writeValueAsString(doc.asString());
        if (doc.isList()) {
            StringBuilder sb = new StringBuilder("[");
            List<Document> list = doc.asList();
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(documentToJson(list.get(i)));
            }
            sb.append("]");
            return sb.toString();
        }
        if (doc.isMap()) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Document> e : doc.asMap().entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append(objectMapper.writeValueAsString(e.getKey())).append(":").append(documentToJson(e.getValue()));
            }
            sb.append("}");
            return sb.toString();
        }
        return "null";
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
                    if (isNovaModel(currentModel)) {
                        if (i > 0) {
                            log.warn("Falling back from {} to Nova model {} via Converse API", candidates.get(i - 1), currentModel);
                        }
                        return invokeNovaWithToolViaConverse(currentModel, systemPrompt, userText, toolJson);
                    }

                    // Anthropic path
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

                    InvokeModelRequest request = InvokeModelRequest.builder()
                            .modelId(currentModel)
                            .contentType("application/json")
                            .accept("application/json")
                            .body(SdkBytes.fromUtf8String(requestJson))
                            .build();

                    InvokeModelResponse response = bedrockRuntimeClient.invokeModel(request);
                    JsonNode root = objectMapper.readTree(response.body().asUtf8String());

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
        } catch (ThrottlingException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("Failed to invoke Bedrock with tool: {}", e.getMessage(), e);
            throw e;
        } catch (Exception e) {
            log.error("Failed to invoke Bedrock with tool: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to invoke Bedrock with tool: " + e.getMessage(), e);
        }
    }
}
