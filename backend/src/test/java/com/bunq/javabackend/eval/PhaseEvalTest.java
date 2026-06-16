package com.bunq.javabackend.eval;

import com.bunq.javabackend.model.enums.BedrockModel;
import com.bunq.javabackend.service.ai.bedrock.BedrockService;
import com.bunq.javabackend.service.ai.bedrock.GapScore;
import com.bunq.javabackend.service.ai.bedrock.GapScorer;
import com.bunq.javabackend.service.ai.bedrock.MatchResult;
import com.bunq.javabackend.service.ai.bedrock.MatchableControl;
import com.bunq.javabackend.service.ai.bedrock.MatchableObligation;
import com.bunq.javabackend.service.ai.bedrock.ObligationControlMatcher;
import com.bunq.javabackend.service.ai.bedrock.ToolDefinitions;
import com.bunq.javabackend.service.pipeline.prompts.SystemPrompts;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * LLM eval runner measuring per-phase accuracy across a prompt×model matrix.
 *
 * DISABLED — calls real AWS Bedrock and incurs cost. Run manually:
 *   mvn test -Dtest=PhaseEvalTest -Dsurefire.failIfNoSpecifiedTests=false
 *
 * Control which cells to run via the flags below.
 */
@Disabled("manual eval; calls real Bedrock, costs money")
@SpringBootTest
class PhaseEvalTest {

    // -----------------------------------------------------------------------
    // Matrix control flags — set to false to skip cells and save budget
    // -----------------------------------------------------------------------
    /** Set -Deval.sonnet=false to skip all Sonnet calls. */
    private static final boolean RUN_SONNET = Boolean.parseBoolean(System.getProperty("eval.sonnet", "true"));
    /** Set -Deval.haiku=false to skip all Haiku calls. */
    private static final boolean RUN_HAIKU  = Boolean.parseBoolean(System.getProperty("eval.haiku", "true"));
    /** Set -Deval.old=false to skip all OLD-prompt calls (NEW only). */
    private static final boolean RUN_OLD    = Boolean.parseBoolean(System.getProperty("eval.old", "true"));

    // -----------------------------------------------------------------------
    // Injected services
    // -----------------------------------------------------------------------
    @Autowired private BedrockService            bedrockService;
    @Autowired private ObligationControlMatcher  matcher;
    @Autowired private GapScorer                 gapScorer;
    @Autowired private ObjectMapper              objectMapper;

    // -----------------------------------------------------------------------
    // Internal matrix types
    // -----------------------------------------------------------------------
    private enum PromptVariant { OLD, NEW }

    private record Cell(PromptVariant prompt, BedrockModel model) {
        String label() { return prompt.name() + "×" + model.name(); }
    }

    /** Returns the enabled cells according to the control flags. */
    private List<Cell> cells() {
        List<Cell> out = new ArrayList<>();
        for (PromptVariant pv : PromptVariant.values()) {
            if (pv == PromptVariant.OLD && !RUN_OLD) continue;
            for (BedrockModel m : new BedrockModel[]{BedrockModel.HAIKU, BedrockModel.SONNET}) {
                if (m == BedrockModel.HAIKU && !RUN_HAIKU) continue;
                if (m == BedrockModel.SONNET && !RUN_SONNET) continue;
                out.add(new Cell(pv, m));
            }
        }
        return out;
    }

    private String sid() {
        return "eval-" + UUID.randomUUID().toString().substring(0, 8);
    }

    // -----------------------------------------------------------------------
    // Golden data helpers
    // -----------------------------------------------------------------------
    private JsonNode loadGolden(String filename) throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/golden/" + filename)) {
            if (is == null) throw new IllegalStateException("Missing golden file: " + filename);
            return objectMapper.readTree(is);
        }
    }

    // -----------------------------------------------------------------------
    // Table printing
    // -----------------------------------------------------------------------
    private static void printHeader(String title, String... columns) {
        System.out.println();
        System.out.println("=== " + title + " ===");
        StringBuilder header = new StringBuilder(String.format("%-20s", "CELL"));
        for (String col : columns) header.append(String.format("  %-22s", col));
        System.out.println(header);
        System.out.println("-".repeat(header.length()));
    }

    private static void printRow(String cellLabel, double... values) {
        StringBuilder row = new StringBuilder(String.format("%-20s", cellLabel));
        for (double v : values) row.append(String.format("  %-22s", String.format("%.3f", v)));
        System.out.println(row);
    }

    // -----------------------------------------------------------------------
    // Parsing helpers (mirroring ExtractObligationsStage / ExtractControlsStage)
    // -----------------------------------------------------------------------

    /** Normalises a string: lowercase, collapse whitespace. */
    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase().replaceAll("\\s+", " ").trim();
    }

    private static boolean contains(String haystack, String needle) {
        if (haystack == null || needle == null) return false;
        return norm(haystack).contains(norm(needle));
    }

    /** Returns array node from tool output (obligations or controls). */
    private static JsonNode obligationsArray(JsonNode toolInput) {
        if (toolInput == null || toolInput.isMissingNode()) return null;
        return toolInput.isArray() ? toolInput : toolInput.path("obligations");
    }

    private static JsonNode controlsArray(JsonNode toolInput) {
        if (toolInput == null || toolInput.isMissingNode()) return null;
        return toolInput.isArray() ? toolInput : toolInput.path("controls");
    }

    /** First 30 normalised chars of a snippet — mirrors isGrounded() in stage classes. */
    private static String snippetNeedle(String snippet) {
        String n = norm(snippet);
        return n.length() > 30 ? n.substring(0, 30) : n;
    }

    // -----------------------------------------------------------------------
    // @Test 1 — obligations extraction
    // -----------------------------------------------------------------------

    /**
     * Metrics per cell:
     *   RECALL          — fraction of expected obligations matched (subject_kw ∩ action_kw substring)
     *   OVER_EXTRACTION — for negative cases (expected=[]), fraction where model returned non-empty
     *   DEONTIC_ACCURACY — among matched obligations, fraction with correct deontic
     *   GROUNDING_RATE  — fraction of extracted obligations whose snippet is grounded in regulation_text
     */
    @Test
    void evalObligations() throws Exception {
        JsonNode cases = loadGolden("obligations_cases.json");

        printHeader("OBLIGATIONS EXTRACTION",
                "RECALL", "OVER_EXTRACTION", "DEONTIC_ACCURACY", "GROUNDING_RATE");

        for (Cell cell : cells()) {
            String oblPrompt = cell.prompt() == PromptVariant.OLD
                    ? OldPrompts.EXTRACT_OBLIGATIONS
                    : SystemPrompts.EXTRACT_OBLIGATIONS;

            int totalExpected    = 0;
            int totalMatched     = 0;
            int deonticCorrect   = 0;
            int deonticTotal     = 0;
            int totalExtracted   = 0;
            int groundedCount    = 0;
            int negativeCases    = 0;
            int falsePositiveCases = 0;

            for (JsonNode c : cases) {
                String sid           = sid();
                String regulationText = c.path("regulation_text").asText("");
                JsonNode expectedArr = c.path("expected");
                boolean isNegative   = !expectedArr.isArray() || expectedArr.isEmpty();

                Map<String, String> userInput = Map.of(
                        "regulation_text", regulationText,
                        "regulation_id",   c.path("id").asText("eval"),
                        "article",         "general",
                        "paragraph_id",    "1"
                );

                JsonNode toolInput = bedrockService.invokeModelWithTool(
                        sid, "eval",
                        cell.model().getModelId(),
                        oblPrompt,
                        userInput,
                        ToolDefinitions.EXTRACT_OBLIGATIONS_TOOL
                );

                JsonNode extracted = obligationsArray(toolInput);
                int extractedCount = (extracted != null && extracted.isArray()) ? extracted.size() : 0;
                totalExtracted += extractedCount;

                // Grounding check
                if (extracted != null && extracted.isArray()) {
                    for (JsonNode obl : extracted) {
                        String snippet = obl.path("source_text_snippet").asText(null);
                        if (snippet != null && !snippet.isBlank()) {
                            String needle = snippetNeedle(snippet);
                            if (!needle.isEmpty() && norm(regulationText).contains(needle)) {
                                groundedCount++;
                            }
                        }
                    }
                }

                // Negative case: over-extraction
                if (isNegative) {
                    negativeCases++;
                    if (extractedCount > 0) falsePositiveCases++;
                    continue;
                }

                // Recall & deontic
                for (JsonNode exp : expectedArr) {
                    totalExpected++;
                    String subjectKw = exp.path("subject_kw").asText("");
                    String actionKw  = exp.path("action_kw").asText("");
                    String deontic   = exp.path("deontic").asText("");

                    // Find any extracted obligation matching subject+action keywords
                    boolean matched = false;
                    if (extracted != null && extracted.isArray()) {
                        for (JsonNode obl : extracted) {
                            boolean subjectOk = contains(obl.path("subject").asText(""), subjectKw);
                            boolean actionOk  = contains(obl.path("action").asText(""), actionKw);
                            if (subjectOk && actionOk) {
                                matched = true;
                                // Deontic check
                                deonticTotal++;
                                String rawDeontic = obl.path("deontic").asText("");
                                // Normalise: model may emit "[O]", "O", "obligation" etc.
                                boolean deonticOk = normaliseDeontic(rawDeontic).equals(normaliseDeontic(deontic));
                                if (deonticOk) deonticCorrect++;
                                break;
                            }
                        }
                    }
                    if (matched) totalMatched++;
                }
            }

            double recall         = totalExpected   == 0 ? 1.0 : (double) totalMatched    / totalExpected;
            double overExtraction = negativeCases    == 0 ? 0.0 : (double) falsePositiveCases / negativeCases;
            double deonticAcc     = deonticTotal     == 0 ? 1.0 : (double) deonticCorrect  / deonticTotal;
            double groundingRate  = totalExtracted   == 0 ? 1.0 : (double) groundedCount   / totalExtracted;

            printRow(cell.label(), recall, overExtraction, deonticAcc, groundingRate);
        }
    }

    /** Maps deontic strings to canonical O/F/P. */
    private static String normaliseDeontic(String raw) {
        if (raw == null) return "";
        String l = raw.trim().toLowerCase();
        if (l.contains("obligat") || l.equals("[o]") || l.equals("o")) return "O";
        if (l.contains("forbid")  || l.contains("prohibit") || l.equals("[f]") || l.equals("f")) return "F";
        if (l.contains("permit")  || l.equals("[p]") || l.equals("p")) return "P";
        return raw.trim();
    }

    // -----------------------------------------------------------------------
    // @Test 2 — controls extraction
    // -----------------------------------------------------------------------

    /**
     * Metrics per cell:
     *   RECALL          — fraction of expected controls matched (desc_kw substring in description)
     *   TYPE_ACCURACY   — among matched controls, fraction with correct control_type+category
     *   OVER_EXTRACTION — for negative cases (expected=[]), fraction where model returned non-empty
     *   GROUNDING_RATE  — fraction of extracted controls whose snippet is grounded in policy_text
     */
    @Test
    void evalControls() throws Exception {
        JsonNode cases = loadGolden("controls_cases.json");

        printHeader("CONTROLS EXTRACTION",
                "RECALL", "TYPE_ACCURACY", "OVER_EXTRACTION", "GROUNDING_RATE");

        for (Cell cell : cells()) {
            String ctrlPrompt = cell.prompt() == PromptVariant.OLD
                    ? OldPrompts.EXTRACT_CONTROLS
                    : SystemPrompts.EXTRACT_CONTROLS;

            int totalExpected      = 0;
            int totalMatched       = 0;
            int typeCorrect        = 0;
            int typeTotal          = 0;
            int totalExtracted     = 0;
            int groundedCount      = 0;
            int negativeCases      = 0;
            int falsePositiveCases = 0;

            for (JsonNode c : cases) {
                String sid        = sid();
                String policyText = c.path("policy_text").asText("");
                JsonNode expectedArr = c.path("expected");
                boolean isNegative   = !expectedArr.isArray() || expectedArr.isEmpty();

                Map<String, String> userInput = Map.of(
                        "policy_text", policyText,
                        "policy_id",   c.path("id").asText("eval")
                );

                JsonNode toolInput = bedrockService.invokeModelWithTool(
                        sid, "eval",
                        cell.model().getModelId(),
                        ctrlPrompt,
                        userInput,
                        ToolDefinitions.EXTRACT_CONTROLS_TOOL
                );

                JsonNode extracted = controlsArray(toolInput);
                int extractedCount = (extracted != null && extracted.isArray()) ? extracted.size() : 0;
                totalExtracted += extractedCount;

                // Grounding check
                if (extracted != null && extracted.isArray()) {
                    for (JsonNode ctrl : extracted) {
                        String snippet = ctrl.path("source_text_snippet").asText(null);
                        if (snippet != null && !snippet.isBlank()) {
                            String needle = snippetNeedle(snippet);
                            if (!needle.isEmpty() && norm(policyText).contains(needle)) {
                                groundedCount++;
                            }
                        }
                    }
                }

                if (isNegative) {
                    negativeCases++;
                    if (extractedCount > 0) falsePositiveCases++;
                    continue;
                }

                for (JsonNode exp : expectedArr) {
                    totalExpected++;
                    String descKw       = exp.path("desc_kw").asText("");
                    String expType      = exp.path("control_type").asText("");   // e.g. "technical"
                    String expCategory  = exp.path("category").asText("");       // e.g. "detective"

                    boolean matched = false;
                    if (extracted != null && extracted.isArray()) {
                        for (JsonNode ctrl : extracted) {
                            if (contains(ctrl.path("description").asText(""), descKw)) {
                                matched = true;
                                typeTotal++;
                                // Both control_type AND category must match
                                boolean typeOk = contains(ctrl.path("control_type").asText(""), expType)
                                        || contains(ctrl.path("type").asText(""), expType);
                                boolean catOk  = contains(ctrl.path("category").asText(""), expCategory);
                                if (typeOk && catOk) typeCorrect++;
                                break;
                            }
                        }
                    }
                    if (matched) totalMatched++;
                }
            }

            double recall         = totalExpected   == 0 ? 1.0 : (double) totalMatched    / totalExpected;
            double typeAcc        = typeTotal        == 0 ? 1.0 : (double) typeCorrect     / typeTotal;
            double overExtraction = negativeCases    == 0 ? 0.0 : (double) falsePositiveCases / negativeCases;
            double groundingRate  = totalExtracted   == 0 ? 1.0 : (double) groundedCount   / totalExtracted;

            printRow(cell.label(), recall, typeAcc, overExtraction, groundingRate);
        }
    }

    // -----------------------------------------------------------------------
    // @Test 3 — obligation→control mapping
    // -----------------------------------------------------------------------

    /**
     * NOTE: matcher.match() always uses NEW prompts (SystemPrompts.MATCH_OBLIGATIONS_TO_CONTROLS).
     * OLD vs NEW comparison is skipped for this phase — only model (HAIKU vs SONNET) is compared.
     * The OLD variant is intentionally excluded from the mapping matrix.
     *
     * Metrics per model:
     *   GAP_STATUS_ACCURACY — fraction of (obligation,control) pairs where
     *                          (score>=50 → "satisfied", else "partial") matches expected_gap_status
     *   HALLUCINATED_REF    — fraction of returned control_ids not present in candidate_controls
     */
    @Test
    void evalMapping() throws Exception {
        JsonNode cases = loadGolden("mapping_cases.json");

        printHeader("OBLIGATION→CONTROL MAPPING  [NEW prompt only; model comparison]",
                "GAP_STATUS_ACCURACY", "HALLUCINATED_REF");

        // Only compare by model — skip OLD prompt for mapping (see note above)
        List<BedrockModel> models = new ArrayList<>();
        if (RUN_HAIKU) models.add(BedrockModel.HAIKU);
        if (RUN_SONNET) models.add(BedrockModel.SONNET);

        for (BedrockModel model : models) {
            int totalPairs       = 0;
            int statusCorrect    = 0;
            int totalReturned    = 0;
            int hallucinated     = 0;

            for (JsonNode c : cases) {
                String sid = sid();

                // Build MatchableObligation
                JsonNode oblNode = c.path("obligation");
                MatchableObligation obl = new MatchableObligation(
                        c.path("id").asText("eval"),
                        oblNode.path("subject").asText(""),
                        oblNode.path("action").asText(""),
                        oblNode.path("risk_category").asText(null),
                        null
                );

                // Build candidate controls
                JsonNode candidatesNode = c.path("candidate_controls");
                List<MatchableControl> candidates = new ArrayList<>();
                List<String> candidateIds = new ArrayList<>();
                if (candidatesNode.isArray()) {
                    for (JsonNode cn : candidatesNode) {
                        String cid = cn.path("control_id").asText(null);
                        candidateIds.add(cid);
                        List<String> standards = new ArrayList<>();
                        JsonNode stdsNode = cn.path("mapped_standards");
                        if (stdsNode.isArray()) stdsNode.forEach(s -> standards.add(s.asText()));
                        candidates.add(new MatchableControl(
                                cid,
                                cn.path("description").asText(""),
                                cn.path("category").asText(null),
                                standards
                        ));
                    }
                }

                // Build expected map: controlId → expected_gap_status
                Map<String, String> expectedStatus = new HashMap<>();
                JsonNode expectedArr = c.path("expected");
                if (expectedArr.isArray()) {
                    for (JsonNode e : expectedArr) {
                        expectedStatus.put(e.path("control_id").asText(""), e.path("expected_gap_status").asText(""));
                    }
                }

                // Invoke matcher
                List<MatchResult> results = matcher.match(sid, "eval", obl, candidates, model);

                for (MatchResult r : results) {
                    totalReturned++;

                    // Hallucination check
                    if (r.controlId() == null || !candidateIds.contains(r.controlId())) {
                        hallucinated++;
                    }

                    // Gap status accuracy
                    String predictedStatus = r.confidence() >= 50.0 ? "satisfied" : "partial";
                    String expected = expectedStatus.get(r.controlId());
                    if (expected != null) {
                        totalPairs++;
                        if (expected.equals(predictedStatus)) statusCorrect++;
                    }
                }
            }

            double gapStatusAcc    = totalPairs   == 0 ? 1.0 : (double) statusCorrect / totalPairs;
            double hallucinatedRef = totalReturned == 0 ? 0.0 : (double) hallucinated  / totalReturned;

            // Label as "NEW×<MODEL>" to make it clear OLD was skipped
            printRow("NEW×" + model.name(), gapStatusAcc, hallucinatedRef);
        }
    }

    // -----------------------------------------------------------------------
    // @Test 4 — gap scoring
    // -----------------------------------------------------------------------

    /**
     * NOTE: gapScorer.score() always uses SystemPrompts.SCORE_GAP (NEW).
     * OLD prompt is also tested by invoking bedrockService.invokeModelWithTool directly with OldPrompts.SCORE_GAP.
     *
     * Metrics per cell:
     *   RESIDUAL_IN_RANGE    — fraction of cases where residualRisk() ∈ [expected_residual_range[0], [1]]
     *   ESCALATION_ACCURACY  — fraction of cases where escalationRequired() == expect_escalation
     */
    @Test
    void evalGap() throws Exception {
        JsonNode cases = loadGolden("gap_cases.json");

        printHeader("GAP SCORING",
                "RESIDUAL_IN_RANGE", "ESCALATION_ACCURACY");

        for (Cell cell : cells()) {
            int total           = 0;
            int residualInRange = 0;
            int escalationOk    = 0;

            for (JsonNode c : cases) {
                String sid = sid();

                JsonNode oblNode = c.path("obligation");
                MatchableObligation obl = new MatchableObligation(
                        c.path("id").asText("eval"),
                        oblNode.path("subject").asText(""),
                        oblNode.path("action").asText(""),
                        oblNode.path("risk_category").asText(null),
                        oblNode.path("regulatory_penalty").asText(null)
                );

                double expectedLo = c.path("expected_residual_range").get(0).asDouble(0.0);
                double expectedHi = c.path("expected_residual_range").get(1).asDouble(1.0);
                boolean expectEsc = c.path("expect_escalation").asBoolean(false);

                GapScore score;
                if (cell.prompt() == PromptVariant.NEW) {
                    score = gapScorer.score(sid, "eval", obl, cell.model());
                } else {
                    // OLD prompt path: invoke directly with OldPrompts.SCORE_GAP
                    score = invokeGapScorerWithPrompt(sid, obl, cell.model(), OldPrompts.SCORE_GAP);
                }

                total++;
                if (score.residualRisk() >= expectedLo && score.residualRisk() <= expectedHi) {
                    residualInRange++;
                }
                if (score.escalationRequired() == expectEsc) {
                    escalationOk++;
                }
            }

            double residualRate = total == 0 ? 1.0 : (double) residualInRange / total;
            double escalationAcc = total == 0 ? 1.0 : (double) escalationOk   / total;

            printRow(cell.label(), residualRate, escalationAcc);
        }
    }

    /**
     * Invokes gap scoring with an arbitrary prompt, bypassing GapScorer's hardcoded SystemPrompts.SCORE_GAP.
     * Replicates GapScorer.score() logic with a custom system prompt.
     */
    private GapScore invokeGapScorerWithPrompt(String sid, MatchableObligation obl,
                                                BedrockModel model, String systemPrompt) {
        try {
            Map<String, Object> userInput = new HashMap<>();
            userInput.put("obligation_id",     obl.id());
            userInput.put("obligation_subject", obl.subject());
            userInput.put("obligation_action",  obl.action());
            userInput.put("risk_category",      obl.riskCategory());
            userInput.put("regulatory_penalty", obl.regulatoryPenalty());

            JsonNode toolInput = bedrockService.invokeModelWithTool(
                    sid, "eval",
                    model.getModelId(),
                    systemPrompt,
                    userInput,
                    ToolDefinitions.SCORE_GAP_TOOL
            );

            // Reconstruct residual risk from raw fields (mirrors GapScorer.buildGapScore)
            Double severity      = nullableDouble(toolInput, "severity");
            Double likelihood    = nullableDouble(toolInput, "likelihood");
            Double detectability = nullableDouble(toolInput, "detectability");
            Double blastRadius   = nullableDouble(toolInput, "blast_radius");
            Double recoverability= nullableDouble(toolInput, "recoverability");
            double residualRisk  = 0.4 * nz(severity) + 0.25 * nz(likelihood)
                    + 0.15 * nz(detectability) + 0.10 * nz(blastRadius) + 0.10 * nz(recoverability);

            boolean escalation = toolInput.path("escalation_required").asBoolean(false);
            String narrative   = toolInput.path("narrative").asText(null);

            return new GapScore(narrative, escalation, severity, likelihood, detectability,
                    blastRadius, recoverability, residualRisk, null, List.of());
        } catch (Exception e) {
            return new GapScore(null, false, null, null, null, null, null, 0.0, null, List.of());
        }
    }

    private static Double nullableDouble(JsonNode node, String field) {
        JsonNode n = node.path(field);
        return n.isMissingNode() || n.isNull() ? null : n.asDouble();
    }

    private static double nz(Double v) { return v != null ? v : 0.0; }

    // -----------------------------------------------------------------------
    // @Test 5 — narrative generation (hallucination / forbidden strings)
    // -----------------------------------------------------------------------

    /**
     * Mirrors NarrateStage.generateNarrative(): invokeModel with HAIKU,
     * system=SystemPrompts.NARRATE_EXEC_SUMMARY, user contains top_gaps from case.
     *
     * Metrics per cell (HAIKU only for narration, as per production; Sonnet cell included if RUN_SONNET):
     *   FORBIDDEN_RATE — fraction of cases where output contains at least one forbidden_string
     *                    (target: ~0.0 — model must not hallucinate unsupported citations)
     */
    @Test
    void evalNarrate() throws Exception {
        JsonNode cases = loadGolden("narrate_cases.json");

        printHeader("NARRATIVE GENERATION (hallucination)",
                "FORBIDDEN_RATE", "CASES_WITH_VIOLATION");

        for (Cell cell : cells()) {
            // Narrate stage uses a single system prompt (no OLD variant tracked here).
            // OLD vs NEW prompt is not applicable for narration — we run the same prompt for both
            // PromptVariant values so the model comparison is still meaningful.
            String narratePrompt = SystemPrompts.NARRATE_EXEC_SUMMARY;

            int total      = 0;
            int violations = 0;

            for (JsonNode c : cases) {
                String sid = sid();
                total++;

                // Build top_gaps list mirroring NarrateStage
                List<Map<String, Object>> topGaps = new ArrayList<>();
                JsonNode gapsArr = c.path("gaps");
                if (gapsArr.isArray()) {
                    for (JsonNode g : gapsArr) {
                        Map<String, Object> entry = new HashMap<>();
                        entry.put("obligation_id", g.path("obligation_id").asText(""));
                        entry.put("narrative",     g.path("narrative").asText(""));
                        entry.put("escalation",    false);
                        entry.put("regulation",    g.path("regulation").asText(""));
                        entry.put("article",       g.path("article").asText(""));
                        entry.put("section",       "");
                        entry.put("source_text",   g.path("source_text").asText(""));
                        topGaps.add(entry);
                    }
                }

                Map<String, Object> userInput = new HashMap<>();
                userInput.put("overall_severity", "amber");
                userInput.put("gap_count",        topGaps.size());
                userInput.put("mapping_count",    0);
                userInput.put("top_gaps",         topGaps);

                String requestJson;
                try {
                    requestJson = objectMapper.writeValueAsString(Map.of(
                            "anthropic_version", "bedrock-2023-05-31",
                            "max_tokens", 512,
                            "system", narratePrompt,
                            "messages", List.of(Map.of(
                                    "role", "user",
                                    "content", objectMapper.writeValueAsString(userInput)
                            ))
                    ));
                } catch (Exception e) {
                    System.err.println("Failed to build narrate request for " + c.path("id").asText() + ": " + e.getMessage());
                    continue;
                }

                JsonNode response = bedrockService.invokeModel(
                        sid, "eval",
                        cell.model().getModelId(),
                        requestJson
                );

                String text = "";
                JsonNode content = response.path("content");
                if (content.isArray() && !content.isEmpty()) {
                    text = content.get(0).path("text").asText("");
                }

                // Check forbidden strings
                JsonNode forbiddenArr = c.path("forbidden_strings");
                boolean violated = false;
                if (forbiddenArr.isArray()) {
                    for (JsonNode fs : forbiddenArr) {
                        String forbidden = fs.asText("");
                        if (!forbidden.isBlank() && text.contains(forbidden)) {
                            System.out.printf("  [%s] case=%s  VIOLATION: contains \"%s\"%n",
                                    cell.label(), c.path("id").asText(), forbidden);
                            violated = true;
                        }
                    }
                }
                if (violated) violations++;
            }

            double forbiddenRate = total == 0 ? 0.0 : (double) violations / total;
            printRow(cell.label(), forbiddenRate, violations);
        }
    }
}
