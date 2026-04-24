package com.bunq.javabackend.service.bedrock;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class ToolDefinitions {

    public static final String EXTRACT_OBLIGATIONS_TOOL = loadResource("/prompts/tools/extract_obligations.json");
    public static final String EXTRACT_CONTROLS_TOOL = loadResource("/prompts/tools/extract_controls.json");
    public static final String MATCH_OBLIGATION_TO_CONTROLS_TOOL = loadResource("/prompts/tools/match_obligation_to_controls.json");
    public static final String SCORE_GAP_TOOL = loadResource("/prompts/tools/score_gap.json");
    public static final String GROUND_CHECK_TOOL = loadResource("/prompts/tools/ground_check.json");
    public static final String EXTRACT_COUNTERPARTIES_TOOL = loadResource("/prompts/tools/extract_counterparties_from_brief.json");

    private ToolDefinitions() {}

    private static String loadResource(String path) {
        try (InputStream is = ToolDefinitions.class.getResourceAsStream(path)) {
            if (is == null) return "{}";
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load tool definition: " + path, e);
        }
    }
}
