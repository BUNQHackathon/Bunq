package com.bunq.javabackend.service.ai.bedrock;

import com.bunq.javabackend.model.enums.BedrockModel;
import com.bunq.javabackend.service.observability.SessionCostService;
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
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class BedrockService {

    // B4: retry constants — retry the same model before falling through the chain
    private static final int MAX_SAME_MODEL_RETRIES = 3;
    private static final long BASE_BACKOFF_MS = 100L;

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
    private final SessionCostService sessionCostService;

    public BedrockService(BedrockRuntimeClient bedrockRuntimeClient,
                          ObjectMapper objectMapper,
                          @Value("${bedrock.max-concurrent:30}") int maxConcurrent,
                          SessionCostService sessionCostService) {
        this.bedrockRuntimeClient = bedrockRuntimeClient;
        this.objectMapper = objectMapper;
        this.bedrockPermits = new Semaphore(maxConcurrent);
        this.sessionCostService = sessionCostService;
    }

    /** Resolves a raw modelId string to the matching BedrockModel enum, or null if not found. */
    private static BedrockModel resolveModel(String modelId) {
        for (BedrockModel m : BedrockModel.values()) {
            if (m.getModelId().equals(modelId)) return m;
        }
        return null;
    }

    /**
     * B4: Sleeps with exponential backoff + jitter before a retry.
     * Re-interrupts and rethrows as RuntimeException on InterruptedException.
     */
    private static void backoffSleep(int attempt) {
        long delay = BASE_BACKOFF_MS * (1L << attempt) + ThreadLocalRandom.current().nextLong(0, 100);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during Bedrock backoff sleep", ie);
        }
    }

    /**
     * Invokes a Bedrock model with full fallback chain, backoff, and cost recording.
     *
     * @param sessionId  Session to attribute cost to; null silently skips cost recording.
     * @param stage      Pipeline stage name (e.g. "narrate"); null silently skips cost recording.
     * @param modelId    Primary model ID to attempt first.
     * @param requestJson Anthropic-shaped JSON request body.
     */
    public JsonNode invokeModel(String sessionId, String stage, String modelId, String requestJson) {
        List<String> candidates = new java.util.ArrayList<>();
        candidates.add(modelId);
        candidates.addAll(FALLBACK_CHAIN.getOrDefault(modelId, List.of()));

        ThrottlingException lastThrottle = null;
        for (int i = 0; i < candidates.size(); i++) {
            String currentModel = candidates.get(i);

            // B4: retry the same model up to MAX_SAME_MODEL_RETRIES times before falling through
            for (int attempt = 0; attempt <= MAX_SAME_MODEL_RETRIES; attempt++) {
                if (attempt > 0) {
                    log.warn("Model {} throttled, retry attempt {}/{} with backoff", currentModel, attempt, MAX_SAME_MODEL_RETRIES);
                    backoffSleep(attempt - 1);
                }

                // B5b: bounded semaphore acquire with 60s timeout.
                // Backpressure on one model falls through to the next in the chain (Bonus fix).
                boolean acquired;
                try {
                    acquired = bedrockPermits.tryAcquire(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted waiting for Bedrock permit", e);
                }
                if (!acquired) {
                    log.warn("Bedrock semaphore timeout for model {}; trying next in chain", currentModel);
                    break; // advance to next model in fallback chain
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
                        if (!usage.isMissingNode() && sessionId != null && stage != null) {
                            int cacheCreation = usage.path("cache_creation_input_tokens").asInt();
                            int cacheRead     = usage.path("cache_read_input_tokens").asInt();
                            int inputTok      = usage.path("input_tokens").asInt();
                            int outputTok     = usage.path("output_tokens").asInt();
                            log.info("Bedrock usage — cache_creation={} cache_read={} input={} output={}",
                                    cacheCreation, cacheRead, inputTok, outputTok);
                            BedrockModel resolvedModel = resolveModel(currentModel);
                            if (resolvedModel != null) {
                                sessionCostService.recordCall(sessionId, stage, resolvedModel,
                                        inputTok, outputTok, cacheCreation, cacheRead);
                            }
                        }
                        return root;
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to parse Bedrock response", e);
                    }
                } catch (ThrottlingException e) {
                    lastThrottle = e;
                    if (attempt < MAX_SAME_MODEL_RETRIES) {
                        // will retry same model
                    } else {
                        // exhausted retries for this model; fall through to next in chain
                        if (i + 1 < candidates.size()) {
                            log.warn("Model {} throttled after {} retries, falling back to {}", currentModel, MAX_SAME_MODEL_RETRIES, candidates.get(i + 1));
                        }
                    }
                } finally {
                    bedrockPermits.release();
                }
            }
        }
        if (lastThrottle != null) throw lastThrottle;
        throw new BedrockBackpressureException("bedrock_backpressure_timeout_60s: all models in chain exhausted");
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
    /**
     * Invokes a Bedrock model with tool-use, full fallback chain, backoff, and cost recording.
     *
     * @param sessionId   Session to attribute cost to; null silently skips cost recording.
     * @param stage       Pipeline stage name (e.g. "extract_obligations"); null skips cost recording.
     * @param modelId     Primary model ID to attempt first.
     * @param systemPrompt Anthropic system prompt string.
     * @param userInput   Object serialised as JSON and sent as the user message.
     * @param toolJson    Tool definition JSON string.
     */
    public JsonNode invokeModelWithTool(String sessionId, String stage,
                                        String modelId, String systemPrompt,
                                        Object userInput, String toolJson) {
        try {
            String userText = objectMapper.writeValueAsString(userInput);

            // B2-extended: also mark the tool definition as cacheable so Bedrock caches
            // the tools block (typically 1-3k tokens) alongside the system prompt.
            // Done once per call before the retry loop; same input toolJson → byte-identical output.
            String cachedToolJson;
            try {
                JsonNode toolNode = objectMapper.readTree(toolJson);
                if (toolNode.isObject()) {
                    ObjectNode toolObj = (ObjectNode) toolNode;
                    ObjectNode cacheControl = objectMapper.createObjectNode();
                    cacheControl.put("type", "ephemeral");
                    cacheControl.put("ttl", "1h");
                    toolObj.set("cache_control", cacheControl);
                    cachedToolJson = objectMapper.writeValueAsString(toolObj);
                } else {
                    cachedToolJson = toolJson;
                }
            } catch (Exception e) {
                log.warn("Failed to add cache_control to tool definition, falling back to uncached tools: {}", e.getMessage());
                cachedToolJson = toolJson;
            }

            List<String> candidates = new java.util.ArrayList<>();
            candidates.add(modelId);
            candidates.addAll(FALLBACK_CHAIN.getOrDefault(modelId, List.of()));

            ThrottlingException lastThrottle = null;
            for (int i = 0; i < candidates.size(); i++) {
                String currentModel = candidates.get(i);

                // B4: retry the same model up to MAX_SAME_MODEL_RETRIES times before falling through
                for (int attempt = 0; attempt <= MAX_SAME_MODEL_RETRIES; attempt++) {
                    if (attempt > 0) {
                        log.warn("Model {} throttled, retry attempt {}/{} with backoff", currentModel, attempt, MAX_SAME_MODEL_RETRIES);
                        backoffSleep(attempt - 1);
                    }

                    // B5b: bounded semaphore acquire with 60s timeout.
                    // Backpressure on one model falls through to the next in the chain (Bonus fix).
                    boolean acquired;
                    try {
                        acquired = bedrockPermits.tryAcquire(60, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted waiting for Bedrock permit", e);
                    }
                    if (!acquired) {
                        log.warn("Bedrock semaphore timeout for model {}; trying next in chain", currentModel);
                        break; // advance to next model in fallback chain
                    }

                    try {
                        if (isNovaModel(currentModel)) {
                            if (i > 0) {
                                log.warn("Falling back from {} to Nova model {} via Converse API", candidates.get(i - 1), currentModel);
                            }
                            return invokeNovaWithToolViaConverse(currentModel, systemPrompt, userText, toolJson);
                        }

                        // Anthropic path
                        // B1: temperature=0 for deterministic/reproducible tool-use calls
                        // B2: cache_control ttl extended to 1h for longer prompt cache retention
                        // NOTE: As of 2026-04, AWS Bedrock does not expose an anthropic-beta header
                        // mechanism via InvokeModelRequest. The "ttl" field in cache_control is sent
                        // as-is in the JSON body; whether Bedrock honours it depends on backend support.
                        // If extended-cache-ttl is not yet active on Bedrock, this field is silently
                        // ignored and the default TTL applies. Re-evaluate when Bedrock publishes
                        // support for anthropic-beta: extended-cache-ttl-2025-04-11.
                        String requestJson = """
                                {
                                  "anthropic_version": "bedrock-2023-05-31",
                                  "max_tokens": 32768,
                                  "temperature": 0,
                                  "system": [
                                    {
                                      "type": "text",
                                      "text": %s,
                                      "cache_control": {"type": "ephemeral", "ttl": "1h"}
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
                                cachedToolJson,
                                objectMapper.writeValueAsString(userText));

                        InvokeModelRequest request = InvokeModelRequest.builder()
                                .modelId(currentModel)
                                .contentType("application/json")
                                .accept("application/json")
                                .body(SdkBytes.fromUtf8String(requestJson))
                                .build();

                        InvokeModelResponse response = bedrockRuntimeClient.invokeModel(request);
                        JsonNode root = objectMapper.readTree(response.body().asUtf8String());

                        // B11: record cost for this tool call
                        JsonNode usageTool = root.path("usage");
                        if (!usageTool.isMissingNode() && sessionId != null && stage != null) {
                            int cacheCreation = usageTool.path("cache_creation_input_tokens").asInt();
                            int cacheRead     = usageTool.path("cache_read_input_tokens").asInt();
                            int inputTok      = usageTool.path("input_tokens").asInt();
                            int outputTok     = usageTool.path("output_tokens").asInt();
                            log.info("Bedrock usage — cache_creation={} cache_read={} input={} output={}",
                                    cacheCreation, cacheRead, inputTok, outputTok);
                            BedrockModel resolvedModel = resolveModel(currentModel);
                            if (resolvedModel != null) {
                                sessionCostService.recordCall(sessionId, stage, resolvedModel,
                                        inputTok, outputTok, cacheCreation, cacheRead);
                            }
                        }

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
                        if (attempt < MAX_SAME_MODEL_RETRIES) {
                            // will retry same model
                        } else {
                            // exhausted retries for this model; fall through to next in chain
                            if (i + 1 < candidates.size()) {
                                log.warn("Model {} throttled after {} retries, falling back to {}", currentModel, MAX_SAME_MODEL_RETRIES, candidates.get(i + 1));
                            }
                        }
                    } finally {
                        bedrockPermits.release();
                    }
                }
            }
            if (lastThrottle != null) throw lastThrottle;
            throw new BedrockBackpressureException("bedrock_backpressure_timeout_60s: all models in chain exhausted");
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
