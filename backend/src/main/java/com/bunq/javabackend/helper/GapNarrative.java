package com.bunq.javabackend.helper;

import com.bunq.javabackend.model.gap.RecommendedAction;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

public final class GapNarrative {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GapNarrative() {}

    /**
     * Returns the narrative with any leaked tool-parameter XML stripped off the end.
     * Trailing whitespace, quotes, and commas that precede the marker are also removed.
     */
    public static String clean(String narrative) {
        if (narrative == null) return null;
        int idx = narrative.indexOf("<parameter name=");
        if (idx < 0) return narrative;
        // Strip trailing whitespace, '"', and ',' before the marker
        int end = idx;
        while (end > 0) {
            char c = narrative.charAt(end - 1);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '"' || c == ',') {
                end--;
            } else {
                break;
            }
        }
        return narrative.substring(0, end);
    }

    /**
     * Recovers a list of {@link RecommendedAction} from pseudo-XML leaked into the narrative.
     * Returns an empty list on any parse error or if the marker is absent.
     */
    public static List<RecommendedAction> recoverActions(String narrative) {
        if (narrative == null) return List.of();
        String marker = "<parameter name=\"recommended_actions\">";
        int markerIdx = narrative.indexOf(marker);
        if (markerIdx < 0) return List.of();

        try {
            int start = narrative.indexOf('[', markerIdx + marker.length());
            if (start < 0) return List.of();

            // Scan forward to find the matching ']', respecting string literals
            int depth = 0;
            boolean inString = false;
            int i = start;
            int end = -1;
            while (i < narrative.length()) {
                char c = narrative.charAt(i);
                if (inString) {
                    if (c == '\\') {
                        i += 2; // skip escaped char
                        continue;
                    } else if (c == '"') {
                        inString = false;
                    }
                } else {
                    if (c == '"') {
                        inString = true;
                    } else if (c == '[') {
                        depth++;
                    } else if (c == ']') {
                        depth--;
                        if (depth == 0) {
                            end = i;
                            break;
                        }
                    }
                }
                i++;
            }
            if (end < 0) return List.of();

            String json = narrative.substring(start, end + 1);
            JsonNode arr = MAPPER.readTree(json);
            if (!arr.isArray()) return List.of();

            List<RecommendedAction> actions = new ArrayList<>();
            for (JsonNode node : arr) {
                RecommendedAction a = new RecommendedAction();
                a.setAction(node.path("action").asText(null));
                a.setSuggestedOwner(node.path("suggested_owner").asText(null));
                actions.add(a);
            }
            return actions;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Strips lightweight Markdown formatting from a string so it renders cleanly in
     * plain-text contexts such as PDF paragraphs.
     * <ul>
     *   <li>Returns {@code null} if the input is {@code null}.</li>
     *   <li>Removes bold/italic markers: {@code **}, {@code __}, and standalone {@code *}/{@code _}.</li>
     *   <li>Removes leading heading markers at line starts ({@code # }, {@code ## }, …).</li>
     *   <li>Converts list-item starts ({@code - }, {@code * }, {@code + }) to {@code • }.</li>
     *   <li>Removes inline-code backticks.</li>
     *   <li>Collapses three or more consecutive newlines to two.</li>
     * </ul>
     */
    public static String stripMarkdown(String s) {
        if (s == null) return null;

        // Remove bold/italic: **, __, then lone * or _ (word-boundary emphasis)
        s = s.replace("**", "").replace("__", "");
        s = s.replaceAll("(?<![\\w])([*_])(?!\\1)(.+?)\\1(?![\\w])", "$2");
        // Remove remaining lone * or _ used as emphasis markers
        s = s.replaceAll("(?m)(?<![\\w])[*_](?![\\w])", "");

        // Remove leading heading markers at line starts (e.g. "## Heading")
        s = s.replaceAll("(?m)^#{1,6}\\s+", "");

        // Convert list-item starts to bullet
        s = s.replaceAll("(?m)^[ \\t]*[-*+]\\s+", "• ");

        // Remove inline-code backticks
        s = s.replace("`", "");

        // Collapse 3+ consecutive newlines to 2
        s = s.replaceAll("\\n{3,}", "\n\n");

        return s;
    }

    /**
     * Recovers the escalation_required flag leaked into the narrative.
     * Returns false if absent or on any error.
     */
    public static boolean recoverEscalation(String narrative) {
        if (narrative == null) return false;
        String marker = "<parameter name=\"escalation_required\">";
        int markerIdx = narrative.indexOf(marker);
        if (markerIdx < 0) return false;
        try {
            String rest = narrative.substring(markerIdx + marker.length()).stripLeading();
            return rest.toLowerCase().startsWith("true");
        } catch (Exception e) {
            return false;
        }
    }
}
