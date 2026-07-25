package com.bunq.javabackend.eval;

import com.bunq.javabackend.service.ai.bedrock.BedrockService;
import com.bunq.javabackend.service.ai.bedrock.TitanEmbeddingService;
import com.bunq.javabackend.service.ai.kb.Reranker;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Matcher candidate-retrieval recall eval — measures how often the expected control
 * appears in the top-K candidates produced by Titan embedding cosine similarity.
 *
 * DISABLED — calls real AWS Bedrock (Titan embed + Haiku for HyDE) and incurs cost. Run manually:
 *   mvn test -Dtest=MatcherRecallEvalTest -DfailIfNoTests=false -Deval.recall=true
 *
 * Gating: assumeTrue(-Deval.recall=true) guards the body — absent property → clean skip.
 * @Disabled is intentionally NOT on the class so the assumeTrue gate can fire.
 * Normal mvn test: property absent → test skips (0 cost).
 * To run: mvn test -Dtest=MatcherRecallEvalTest -DfailIfNoTests=false -Deval.recall=true
 */
@SpringBootTest
class MatcherRecallEvalTest {

    /** Set -Deval.recall=true to activate this test when running it directly by name. */
    private static final boolean RUN_RECALL = Boolean.parseBoolean(System.getProperty("eval.recall", "false"));

    /** Haiku model id for HyDE calls. */
    private static final String HYDE_MODEL_ID = "eu.anthropic.claude-haiku-4-5-20251001-v1:0";

    /**
     * Approximate cost per Haiku call (input+output ~500 tokens total, generous upper bound).
     * Haiku pricing: ~$0.80/M input, ~$4.00/M output tokens (as of 2025).
     * Estimated per call: ~400 input tokens * $0.80/M + ~100 output tokens * $4.00/M ≈ $0.00072
     */
    private static final double HAIKU_COST_PER_CALL_USD = 0.00072;

    // -----------------------------------------------------------------------
    // Config-toggle scaffold for retrieval strategies
    // -----------------------------------------------------------------------

    /**
     * EvalConfig — booleans for each candidate-generation enhancement.
     * For baseline run, all are false. Later steps enable richCard, sparse, anchors, hyde.
     * denseK controls how many dense top-N are included (default 30).
     */
    record EvalConfig(boolean richCard, boolean sparse, boolean anchors, boolean hyde, int denseK) {
        static EvalConfig baseline()    { return new EvalConfig(false, false, false, false, 30); }
        static EvalConfig c1()          { return new EvalConfig(true,  false, false, false, 30); }
        static EvalConfig c2()          { return new EvalConfig(true,  false, true,  false, 30); }
        static EvalConfig c3()          { return new EvalConfig(true,  true,  true,  false, 30); }
        /** C4: richCard + anchors + HyDE */
        static EvalConfig c4()          { return new EvalConfig(true,  false, true,  true,  30); }
        /** C5: richCard + anchors + HyDE + dense-K raised 30→50 */
        static EvalConfig c5()          { return new EvalConfig(true,  false, true,  true,  50); }
        /** CK: baseline query + dense top-50 only */
        static EvalConfig ck()          { return new EvalConfig(false, false, false, false, 50); }
    }

    // -----------------------------------------------------------------------
    // Domain records — extended with all fixture fields
    // -----------------------------------------------------------------------

    record ObligationRow(
            String id,
            String subject,
            String action,
            List<String> conditions,
            String label,
            String expectedControlId,
            String note,
            String sourceText
    ) {}

    record ControlRow(
            String id,
            String description,
            List<String> mappedStandards,
            String owner,
            String controlType,
            String category
    ) {}

    // -----------------------------------------------------------------------
    // Anchor lexicon — surface phrases → canonical anchor token
    // Each entry: {canonical, phrase1, phrase2, ...}
    // A phrase matches if the lowercase text contains it.
    // -----------------------------------------------------------------------

    private static final String[][] ANCHOR_LEXICON = {
        // PEP
        { "pep", "pep", "politically exposed" },
        // Sanctions / freezing
        { "sanctions", "sanction", "asset freez", "dow jones", "terrorist financing", "eu list", "congelamento", "freezing" },
        // FIU / suspicious
        { "fiu", "fiu", "uif", "str", "suspicious transaction", "suspicious operation", "operazione sospetta" },
        // CDD / KYC / due diligence
        { "cdd", "due diligence", "kyc", "adeguata verifica", "customer due diligence" },
        // EDD / enhanced
        { "edd", "enhanced due diligence", "rafforzata", "rafforzati", "enhanced customer due diligence" },
        // UBO / beneficial owner
        { "ubo", "ubo", "beneficial owner", "titolare effettivo" },
        // Retention / record keeping
        { "retention", "retention", "record keeping", "recordkeeping", "anni", "years", "data retention" },
        // ATECO
        { "ateco", "ateco" },
        // Biometric
        { "biometric", "biometric", "biometri" },
        // Screening
        { "screening", "screening", "screen" },
        // Monitoring
        { "monitoring", "monitoring", "monitor", "monitoraggio" },
        // Senior management
        { "senior_management", "senior management", "approval", "management approval", "board approval", "autorizzazione" },
        // AML policy
        { "aml_policy", "anti-money laundering policy", "aml policy", "procedure aml" },
        // Reporting
        { "reporting", "reporting", "report", "segnalazione" },
    };

    // -----------------------------------------------------------------------
    // BM25 parameters
    // -----------------------------------------------------------------------
    private static final double BM25_K1 = 1.2;
    private static final double BM25_B  = 0.75;

    private static final Set<String> STOPWORDS = Set.of(
        "a", "an", "the", "and", "or", "of", "in", "to", "for", "is", "are",
        "be", "on", "at", "by", "with", "as", "from", "that", "this", "it",
        "its", "has", "have", "not", "no", "was", "were", "will", "can",
        "which", "all", "any", "their", "they", "each", "per", "must", "shall"
    );

    // -----------------------------------------------------------------------
    // Injected services
    // -----------------------------------------------------------------------

    @Autowired private TitanEmbeddingService embedSvc;
    @Autowired private BedrockService bedrockSvc;
    @Autowired private ObjectMapper objectMapper;

    // -----------------------------------------------------------------------
    // @Test — cumulative eval C0 → C3, then HyDE variants C4, C5, CK
    // -----------------------------------------------------------------------

    @Test
    void evalBaselineRecall() throws Exception {
        Assumptions.assumeTrue(RUN_RECALL,
                "Skipping MatcherRecallEvalTest — pass -Deval.recall=true to activate");

        // Load fixtures
        List<ControlRow> controls    = loadControls();
        List<ObligationRow> obligations = loadObligations();

        System.out.printf("%nLoaded %d controls, %d obligations%n", controls.size(), obligations.size());

        // Identify target obligations (those with a non-null expected_control_id)
        List<ObligationRow> targets = obligations.stream()
                .filter(o -> o.expectedControlId() != null && !o.expectedControlId().isBlank())
                .toList();
        System.out.printf("Target obligations (with expected_control_id): %d%n", targets.size());

        // -----------------------------------------------------------------------
        // PART A: Label sanity check for the 5 persistent misses (no LLM)
        // -----------------------------------------------------------------------
        printLabelSanityCheck(obligations, controls);

        // -----------------------------------------------------------------------
        // 5 persistent-miss IDs
        // -----------------------------------------------------------------------
        List<String> persistentMissOblIds = List.of(
            "OBL-9195d73555c491951f2ee7fb",
            "OBL-776467908fceed61e954b941",
            "OBL-91a78a63215663af021440df",
            "OBL-6f6a5ef6e7a1ba875efe97ea",
            "OBL-c8dba974babb09898e45497b"
        );

        // Weak-label IDs to track rescue (flagged as possibly-wrong labels)
        Set<String> weakLabelIds = Set.of(
            "OBL-776467908fceed61e954b941",
            "OBL-6f6a5ef6e7a1ba875efe97ea"
        );

        // -----------------------------------------------------------------------
        // Run all configs
        // -----------------------------------------------------------------------
        // C0-C3: legacy baseline configs
        // C2: richCard+anchors (recomputed here as reference)
        // C4: richCard+anchors+HyDE
        // C5: richCard+anchors+HyDE+denseK50
        // CK: baseline+denseK50 (is it just K too small?)
        EvalConfig[] configs = {
            EvalConfig.baseline(),
            EvalConfig.c1(),
            EvalConfig.c2(),
            EvalConfig.c3(),
            EvalConfig.c4(),
            EvalConfig.c5(),
            EvalConfig.ck()
        };
        String[] configNames = {
            "C0-baseline",
            "C1-richCard",
            "C2-richCard+anchors",
            "C3-richCard+anchors+sparse",
            "C4-richCard+anchors+HyDE",
            "C5-richCard+anchors+HyDE+K50",
            "CK-baseline+K50"
        };

        // Summary table row
        record ConfigResult(
            String name,
            int hit15, int hit20, int unionHit,
            int numTargets,
            long p50, long p95,
            double estCostUsd,
            Map<String, Integer> rankByOblId,
            Map<String, Boolean> inUnionByOblId,
            // Route tracking for HyDE configs: oblId -> set of routes that surfaced the expected ctrl
            Map<String, Set<String>> routeByOblId
        ) {}

        List<ConfigResult> configResults = new ArrayList<>();

        for (int ci = 0; ci < configs.length; ci++) {
            EvalConfig cfg = configs[ci];
            System.out.printf("%n--- Running %s ---%n", configNames[ci]);

            // Build control texts for this config
            Map<String, String> controlTexts = buildControlTexts(controls, cfg);

            // Embed controls
            System.out.printf("Embedding %d controls for %s...%n", controls.size(), configNames[ci]);
            long embStart = System.currentTimeMillis();
            Map<String, float[]> controlVecs = embedSvc.embedBatch(controlTexts);
            long embMs = System.currentTimeMillis() - embStart;
            System.out.printf("Control embeddings done in %dms (%d/%d succeeded)%n",
                    embMs, controlVecs.size(), controls.size());

            // Build BM25 index for sparse (if enabled)
            Bm25Index bm25 = cfg.sparse() ? new Bm25Index(controlTexts) : null;

            // Build anchor index
            Map<String, Set<String>> anchorToControls = cfg.anchors()
                    ? buildAnchorIndex(controls, controlTexts) : Map.of();

            // Per-obligation retrieval
            int hit15 = 0, hit20 = 0, unionHit = 0;
            long totalChars = 0;
            long hydeCallMs = 0;
            int hydeCallCount = 0;
            long[] latencies = new long[obligations.size()];
            int latIdx = 0;
            Map<String, Integer> rankByOblId = new LinkedHashMap<>();
            Map<String, Boolean> inUnionByOblId = new LinkedHashMap<>();
            Map<String, Set<String>> routeByOblId = new LinkedHashMap<>();

            for (ObligationRow obl : obligations) {
                long t0 = System.currentTimeMillis();

                String queryText = buildQuery(obl, cfg);
                totalChars += queryText.length();

                // HyDE: generate hypothesis text and embed it
                String hydeText = null;
                float[] hydeVec = null;
                if (cfg.hyde()) {
                    long hydeT0 = System.currentTimeMillis();
                    hydeText = callHyde(obl);
                    hydeCallMs += System.currentTimeMillis() - hydeT0;
                    hydeCallCount++;
                    if (hydeText != null && !hydeText.isBlank()) {
                        hydeVec = embedSvc.embed(hydeText);
                    }
                }

                List<String> topK = candidates(obl, cfg, 20, controlVecs, queryText, bm25, anchorToControls, controlTexts, hydeVec, routeByOblId);

                long latMs = System.currentTimeMillis() - t0;
                latencies[latIdx++] = latMs;

                if (obl.expectedControlId() != null && !obl.expectedControlId().isBlank()) {
                    String expected = obl.expectedControlId();
                    int rank = topK.indexOf(expected) + 1;  // 1-based; 0=miss

                    rankByOblId.put(obl.id(), rank);
                    boolean inUnion = topK.contains(expected);
                    inUnionByOblId.put(obl.id(), inUnion);

                    if (rank > 0 && rank <= 15) hit15++;
                    if (rank > 0 && rank <= 20) hit20++;
                    if (inUnion) unionHit++;
                }
            }

            // Add control text chars to cost estimate
            for (ControlRow c : controls) {
                totalChars += controlTexts.getOrDefault(c.id(), "").length();
            }

            // Latency percentiles
            long[] sortedLat = Arrays.copyOf(latencies, latIdx);
            Arrays.sort(sortedLat);
            long p50 = sortedLat[sortedLat.length / 2];
            long p95 = sortedLat[(int) (sortedLat.length * 0.95)];

            // Cost estimate
            // Titan v2 embed: $0.02 per 1M tokens; tokens ≈ chars/4
            long totalTokens = totalChars / 4;
            double embedCostUsd = (double) totalTokens / 1_000_000.0 * 0.02;
            // HyDE: Haiku cost per call
            double hydeCostUsd = cfg.hyde() ? hydeCallCount * HAIKU_COST_PER_CALL_USD : 0.0;
            double estCostUsd = embedCostUsd + hydeCostUsd;

            if (cfg.hyde()) {
                long avgHydeMs = hydeCallCount > 0 ? hydeCallMs / hydeCallCount : 0;
                System.out.printf("HyDE: %d calls, avg latency %dms, est cost $%.5f%n",
                        hydeCallCount, avgHydeMs, hydeCostUsd);
            }

            configResults.add(new ConfigResult(
                configNames[ci], hit15, hit20, unionHit, targets.size(),
                p50, p95, estCostUsd, rankByOblId, inUnionByOblId, routeByOblId
            ));

            System.out.printf("Done: hit@15=%d/%d=%.3f  hit@20=%d/%d=%.3f  union=%d/%d=%.3f%n",
                hit15, targets.size(), (double)hit15/targets.size(),
                hit20, targets.size(), (double)hit20/targets.size(),
                unionHit, targets.size(), (double)unionHit/targets.size());
        }

        // -----------------------------------------------------------------------
        // Print comparison table C0→C3, C2, C4, C5, CK
        // -----------------------------------------------------------------------
        System.out.println();
        System.out.println("=".repeat(120));
        System.out.println("  MATCHER RECALL EVAL — CUMULATIVE COMPARISON");
        System.out.println("=".repeat(120));
        System.out.printf("%-36s  %8s  %8s  %12s  %8s  %8s  %12s%n",
                "CONFIG", "hit@15", "hit@20", "union-recall", "p50ms", "p95ms", "est$");
        System.out.println("-".repeat(120));
        for (ConfigResult r : configResults) {
            System.out.printf("%-36s  %8s  %8s  %12s  %8d  %8d  %12s%n",
                r.name(),
                String.format("%d/%d=%.3f", r.hit15(), r.numTargets(), (double)r.hit15()/r.numTargets()),
                String.format("%d/%d=%.3f", r.hit20(), r.numTargets(), (double)r.hit20()/r.numTargets()),
                String.format("%d/%d=%.3f", r.unionHit(), r.numTargets(), (double)r.unionHit()/r.numTargets()),
                r.p50(), r.p95(),
                String.format("$%.5f", r.estCostUsd())
            );
        }
        System.out.println("=".repeat(120));

        // -----------------------------------------------------------------------
        // Per-target table for C4 (index 4)
        // -----------------------------------------------------------------------
        ConfigResult c4Result = configResults.get(4);
        System.out.println();
        System.out.println("  PER-TARGET TABLE — C4 (richCard + anchors + HyDE)");
        System.out.println("-".repeat(140));
        System.out.printf("%-40s  %-32s  %6s  %9s  %-30s  %s%n",
                "OBLIGATION_ID", "EXPECTED_CONTROL_ID", "RANK", "IN_UNION", "ROUTE", "LABEL");
        System.out.println("-".repeat(140));

        Map<String, String> oblLabel = new LinkedHashMap<>();
        Map<String, String> oblExpected = new LinkedHashMap<>();
        for (ObligationRow o : obligations) {
            if (o.expectedControlId() != null && !o.expectedControlId().isBlank()) {
                oblLabel.put(o.id(), o.label() != null ? o.label() : "");
                oblExpected.put(o.id(), o.expectedControlId());
            }
        }

        for (Map.Entry<String, Integer> e : c4Result.rankByOblId().entrySet()) {
            String oblId = e.getKey();
            int rank = e.getValue();
            String rankStr = rank == 0 ? "MISS" : String.valueOf(rank);
            boolean inUnion = c4Result.inUnionByOblId().getOrDefault(oblId, false);
            String label = oblLabel.getOrDefault(oblId, "");
            String expCtrl = oblExpected.getOrDefault(oblId, "");
            Set<String> routes = c4Result.routeByOblId().getOrDefault(oblId + ":" + expCtrl, Set.of());
            String routeStr = routes.isEmpty() ? "-" : String.join("+", routes);
            System.out.printf("%-40s  %-32s  %6s  %9s  %-30s  %s%n",
                    truncate(oblId, 40),
                    truncate(expCtrl, 32),
                    rankStr,
                    inUnion ? "YES" : "NO",
                    truncate(routeStr, 30),
                    label);
        }
        System.out.println("-".repeat(140));

        // -----------------------------------------------------------------------
        // 5 persistent-miss rescue report
        // -----------------------------------------------------------------------
        System.out.println();
        System.out.println("  PERSISTENT-MISS RESCUE REPORT (C2 vs C4 vs C5 vs CK)");
        System.out.println("-".repeat(100));
        System.out.printf("%-40s  %4s  %4s  %4s  %4s  %-30s%n",
                "OBLIGATION_ID", "C2", "C4", "C5", "CK", "ROUTE_C4");
        System.out.println("-".repeat(100));

        // Config indices: C2=2, C4=4, C5=5, CK=6
        for (String missId : persistentMissOblIds) {
            String expCtrl = oblExpected.getOrDefault(missId, "?");
            Integer c2Rank = configResults.get(2).rankByOblId().get(missId);
            Integer c4Rank = configResults.get(4).rankByOblId().get(missId);
            Integer c5Rank = configResults.get(5).rankByOblId().get(missId);
            Integer ckRank = configResults.get(6).rankByOblId().get(missId);
            String c2s = rankStr(c2Rank);
            String c4s = rankStr(c4Rank);
            String c5s = rankStr(c5Rank);
            String cks = rankStr(ckRank);
            Set<String> routes = configResults.get(4).routeByOblId().getOrDefault(missId + ":" + expCtrl, Set.of());
            String routeStr = routes.isEmpty() ? "MISS" : String.join("+", routes);
            System.out.printf("%-40s  %4s  %4s  %4s  %4s  %-30s%n",
                    truncate(missId, 40), c2s, c4s, c5s, cks, routeStr);
        }
        System.out.println("-".repeat(100));

        // Summary: how many of the 5 misses did HyDE rescue?
        long hydeSavedUnion = persistentMissOblIds.stream()
                .filter(id -> configResults.get(4).inUnionByOblId().getOrDefault(id, false))
                .count();
        long hydeSavedTop20 = persistentMissOblIds.stream()
                .filter(id -> {
                    Integer r = configResults.get(4).rankByOblId().get(id);
                    return r != null && r > 0 && r <= 20;
                })
                .count();
        System.out.printf("%nHyDE (C4) rescued %d/%d persistent misses into union; %d/%d into top-20%n",
                hydeSavedUnion, persistentMissOblIds.size(),
                hydeSavedTop20, persistentMissOblIds.size());

        // -----------------------------------------------------------------------
        // Per-target table for C3 (legacy, kept for continuity)
        // -----------------------------------------------------------------------
        ConfigResult c3Result = configResults.get(3);
        System.out.println();
        System.out.println("  PER-TARGET TABLE — C3 (richCard + anchors + sparse)");
        System.out.println("-".repeat(120));
        System.out.printf("%-40s  %-32s  %6s  %9s  %s%n",
                "OBLIGATION_ID", "EXPECTED_CONTROL_ID", "RANK@C3", "IN_UNION", "LABEL");
        System.out.println("-".repeat(120));

        for (Map.Entry<String, Integer> e : c3Result.rankByOblId().entrySet()) {
            String oblId = e.getKey();
            int rank = e.getValue();
            String rankStr = rank == 0 ? "MISS" : String.valueOf(rank);
            boolean inUnion = c3Result.inUnionByOblId().getOrDefault(oblId, false);
            String label = oblLabel.getOrDefault(oblId, "");
            String expCtrl = oblExpected.getOrDefault(oblId, "");
            System.out.printf("%-40s  %-32s  %6s  %9s  %s%n",
                    truncate(oblId, 40),
                    truncate(expCtrl, 32),
                    rankStr,
                    inUnion ? "YES" : "NO",
                    label);
        }
        System.out.println("-".repeat(120));

        // -----------------------------------------------------------------------
        // Weak-label rescue report
        // -----------------------------------------------------------------------
        System.out.println();
        System.out.println("  WEAK-LABEL RESCUE REPORT (across all configs)");
        System.out.println("-".repeat(100));
        System.out.printf("%-40s", "OBLIGATION_ID");
        for (String n : configNames) System.out.printf("  %6s", truncate(n, 6));
        System.out.println();
        System.out.println("-".repeat(100));
        for (String weakId : weakLabelIds) {
            System.out.printf("%-40s", truncate(weakId, 40));
            for (ConfigResult cr : configResults) {
                Integer rank = cr.rankByOblId().get(weakId);
                System.out.printf("  %6s", rankStr(rank));
            }
            System.out.println();
        }
        System.out.println("-".repeat(100));
        System.out.println();
    }

    // -----------------------------------------------------------------------
    // @Test — H / R / D50 comparison (Cohere rerank configs)
    // Gate: -Deval.recall=true  (same gate as evalBaselineRecall)
    // Run:  mvn test -Dtest=MatcherRecallEvalTest#runComparisonEval -DfailIfNoTests=false -Deval.recall=true
    // -----------------------------------------------------------------------

    @Autowired private Reranker reranker;

    @Test
    void runComparisonEval() throws Exception {
        Assumptions.assumeTrue(RUN_RECALL,
                "Skipping runComparisonEval — pass -Deval.recall=true to activate");

        List<ControlRow> controls       = loadControls();
        List<ObligationRow> obligations = loadObligations();
        List<ObligationRow> targets = obligations.stream()
                .filter(o -> o.expectedControlId() != null && !o.expectedControlId().isBlank())
                .toList();

        System.out.printf("%nLoaded %d controls, %d obligations, %d targets%n",
                controls.size(), obligations.size(), targets.size());

        // Build richCard control texts (same as C2/C3/C4) for dense embedding
        EvalConfig richCfg = EvalConfig.c2(); // richCard=true, anchors=true
        Map<String, String> richTexts = buildControlTexts(controls, richCfg);

        // Build description-only texts for Cohere (plain description, no extras)
        Map<String, String> descTexts = new HashMap<>();
        for (ControlRow c : controls) {
            descTexts.put(c.id(), c.description() != null ? c.description().trim() : "");
        }

        // Precompute dense embeddings for all controls (richCard)
        System.out.println("Embedding " + controls.size() + " controls (richCard) for dense retrieval...");
        long embStart = System.currentTimeMillis();
        Map<String, float[]> controlVecs = embedSvc.embedBatch(richTexts);
        System.out.printf("Control embeddings done in %dms (%d/%d)%n",
                System.currentTimeMillis() - embStart, controlVecs.size(), controls.size());

        // Build BM25 index on richCard texts
        Bm25Index bm25 = new Bm25Index(richTexts);

        // Build anchor index
        Map<String, Set<String>> anchorToControls = buildAnchorIndex(controls, richTexts);

        // Build ordered list of all controls for Cohere (stable order, description text)
        List<Reranker.RankedItem> allControlItems = controls.stream()
                .map(c -> new Reranker.RankedItem(c.id(), descTexts.getOrDefault(c.id(), "")))
                .toList();

        // Per-obligation result accumulators
        // rank = 1-based position in top-K list; 0 = MISS
        Map<String, Integer> rankH   = new LinkedHashMap<>();
        Map<String, Integer> rankR   = new LinkedHashMap<>();
        Map<String, Integer> rankD50 = new LinkedHashMap<>();
        Map<String, Boolean> unionH  = new LinkedHashMap<>();

        long[] latH   = new long[targets.size()];
        long[] latR   = new long[targets.size()];
        long[] latD50 = new long[targets.size()];
        int idx = 0;

        for (ObligationRow obl : targets) {
            String expected = obl.expectedControlId();
            String query = buildObligationSearchText(obl).trim();

            // ---- Config D50: dense top-50 ----
            long t0 = System.currentTimeMillis();
            float[] qVec = embedSvc.embed(query);
            List<String> d50List = List.of();
            if (qVec != null) {
                Map<String, Double> denseScores = new HashMap<>(controlVecs.size());
                for (Map.Entry<String, float[]> e : controlVecs.entrySet()) {
                    denseScores.put(e.getKey(), TitanEmbeddingService.cosine(qVec, e.getValue()));
                }
                d50List = denseScores.entrySet().stream()
                        .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                        .limit(50)
                        .map(Map.Entry::getKey)
                        .toList();
            }
            latD50[idx] = System.currentTimeMillis() - t0;
            int posD50 = d50List.indexOf(expected);
            rankD50.put(obl.id(), posD50 >= 0 ? posD50 + 1 : 0);

            // ---- Config H: hybrid union → Cohere rerank top-20 ----
            t0 = System.currentTimeMillis();
            // Dense top-30
            Set<String> candidateSet = new LinkedHashSet<>();
            if (qVec != null) {
                Map<String, Double> ds = new HashMap<>(controlVecs.size());
                for (Map.Entry<String, float[]> e : controlVecs.entrySet()) {
                    ds.put(e.getKey(), TitanEmbeddingService.cosine(qVec, e.getValue()));
                }
                topN(ds, 30).forEach(candidateSet::add);
            }
            // BM25 top-30
            Map<String, Double> bm25Scores = bm25.score(query);
            topN(bm25Scores, 30).forEach(candidateSet::add);
            // Anchors
            Set<String> oblAnchors = anchorsForObligation(obl);
            for (String anchor : oblAnchors) {
                candidateSet.addAll(anchorToControls.getOrDefault(anchor, Set.of()));
            }
            boolean inUnion = candidateSet.contains(expected);
            unionH.put(obl.id(), inUnion);

            // Cohere rerank the union → top-20
            List<Reranker.RankedItem> unionItems = candidateSet.stream()
                    .map(id -> new Reranker.RankedItem(id, descTexts.getOrDefault(id, "")))
                    .toList();
            List<Reranker.RankedItem> hResult = reranker.rerank(query, unionItems, 20);
            latH[idx] = System.currentTimeMillis() - t0;

            int posH = -1;
            for (int i = 0; i < hResult.size(); i++) {
                if (expected.equals(hResult.get(i).id())) { posH = i; break; }
            }
            rankH.put(obl.id(), posH >= 0 ? posH + 1 : 0);

            // ---- Config R: rerank-all-423 → top-20 ----
            t0 = System.currentTimeMillis();
            List<Reranker.RankedItem> rResult = reranker.rerank(query, allControlItems, 20);
            latR[idx] = System.currentTimeMillis() - t0;

            int posR = -1;
            for (int i = 0; i < rResult.size(); i++) {
                if (expected.equals(rResult.get(i).id())) { posR = i; break; }
            }
            rankR.put(obl.id(), posR >= 0 ? posR + 1 : 0);

            System.out.printf("  [%s] D50=%s H=%s R=%s union=%s  (latD50=%dms latH=%dms latR=%dms)%n",
                    truncate(obl.id(), 30),
                    rankStr(rankD50.get(obl.id())), rankStr(rankH.get(obl.id())), rankStr(rankR.get(obl.id())),
                    inUnion ? "YES" : "NO",
                    latD50[idx], latH[idx], latR[idx]);
            idx++;
        }

        int N = targets.size();

        // Compute recall@K for each config
        long h10  = rankH.values().stream().filter(r -> r > 0 && r <= 10).count();
        long h20  = rankH.values().stream().filter(r -> r > 0 && r <= 20).count();
        long r10  = rankR.values().stream().filter(r -> r > 0 && r <= 10).count();
        long r20  = rankR.values().stream().filter(r -> r > 0 && r <= 20).count();
        long d10  = rankD50.values().stream().filter(r -> r > 0 && r <= 10).count();
        long d20  = rankD50.values().stream().filter(r -> r > 0 && r <= 20).count();
        long uH   = unionH.values().stream().filter(Boolean::booleanValue).count();

        long[] sortedH   = Arrays.copyOf(latH,   idx); Arrays.sort(sortedH);
        long[] sortedR   = Arrays.copyOf(latR,   idx); Arrays.sort(sortedR);
        long[] sortedD50 = Arrays.copyOf(latD50, idx); Arrays.sort(sortedD50);
        long p50H   = sortedH[sortedH.length / 2];
        long p95H   = sortedH[(int)(sortedH.length * 0.95)];
        long p50R   = sortedR[sortedR.length / 2];
        long p95R   = sortedR[(int)(sortedR.length * 0.95)];
        long p50D50 = sortedD50[sortedD50.length / 2];
        long p95D50 = sortedD50[(int)(sortedD50.length * 0.95)];

        // Cost estimates
        // Titan v2 embed: $0.02/1M tokens; tokens ≈ chars/4
        // For D50/H: embed query per obligation (~300 chars avg → ~75 tokens) + controls once (~30 chars avg desc → embedded once above)
        // Main embed cost is in control embeddings (done once), amortised.
        // Per-obligation cost here = query embed only.
        long avgQueryChars = targets.stream()
                .mapToLong(o -> buildObligationSearchText(o).length()).sum() / N;
        double embedCostPerObl = (avgQueryChars / 4.0) / 1_000_000.0 * 0.02;
        // Control embed amortized (done once for all 9): 423 controls * avg richCard len ~200 chars
        double controlEmbedAmortized = (423L * 200 / 4.0) / 1_000_000.0 * 0.02;

        // Cohere rerank pricing: $0.002 per 1000 tokens total (query+docs)
        // Rerank-v3.5 on Bedrock: per search unit = 1000 tokens; ~$0.001 per search unit
        // For R (423 docs): query ~75 tokens + 423 * ~50 tokens desc = ~21225 tokens → ~22 SU → $0.022 per call
        // For H (union ~60-120 docs avg): query ~75 + 80*50 = 4075 tokens → ~5 SU → $0.005 per call
        // Cohere rerank-v3.5 Bedrock pricing: $0.001 per 1000 tokens
        double cohereRperObl = (75.0 + 423 * 50) / 1000.0 * 0.001;
        double cohereHperObl = (75.0 + 80  * 50) / 1000.0 * 0.001;

        double totalCostR   = embedCostPerObl + cohereRperObl;
        double totalCostH   = embedCostPerObl + cohereHperObl;
        double totalCostD50 = embedCostPerObl;

        // Extrapolation to 1254 obligations
        double extrapR   = 1254 * totalCostR;
        double extrapH   = 1254 * totalCostH;
        long   extrapLatR_p50_min = (long)(1254 * p50R / 60000);

        System.out.println();
        System.out.println("=".repeat(110));
        System.out.println("  H / R / D50 COMPARISON — CORRECTED EVAL LABELS");
        System.out.println("=".repeat(110));
        System.out.printf("%-10s  %10s  %10s  %12s  %8s  %8s  %14s%n",
                "CONFIG", "recall@10", "recall@20", "union-recall", "p50ms", "p95ms", "est$/obl");
        System.out.println("-".repeat(110));
        System.out.printf("%-10s  %10s  %10s  %12s  %8d  %8d  %14s%n",
                "H-hybrid",
                fmt(h10, N), fmt(h20, N), fmt(uH, N),
                p50H, p95H, String.format("$%.5f", totalCostH));
        System.out.printf("%-10s  %10s  %10s  %12s  %8d  %8d  %14s%n",
                "R-all423",
                fmt(r10, N), fmt(r20, N), "n/a",
                p50R, p95R, String.format("$%.5f", totalCostR));
        System.out.printf("%-10s  %10s  %10s  %12s  %8d  %8d  %14s%n",
                "D50-dense",
                fmt(d10, N), fmt(d20, N), "n/a",
                p50D50, p95D50, String.format("$%.5f", totalCostD50));
        System.out.println("=".repeat(110));

        System.out.println();
        System.out.println("  PER-TARGET RANK TABLE");
        System.out.println("-".repeat(130));
        System.out.printf("%-40s  %-32s  %6s  %6s  %6s  %8s  %-20s%n",
                "OBLIGATION_ID", "EXPECTED_CONTROL_ID", "H", "R", "D50", "UNION_H", "LABEL");
        System.out.println("-".repeat(130));
        for (ObligationRow obl : targets) {
            System.out.printf("%-40s  %-32s  %6s  %6s  %6s  %8s  %-20s%n",
                    truncate(obl.id(), 40),
                    truncate(obl.expectedControlId(), 32),
                    rankStr(rankH.get(obl.id())),
                    rankStr(rankR.get(obl.id())),
                    rankStr(rankD50.get(obl.id())),
                    unionH.getOrDefault(obl.id(), false) ? "YES" : "NO",
                    obl.label() != null ? obl.label() : "");
        }
        System.out.println("-".repeat(130));

        System.out.println();
        System.out.println("  DECISIVE ANSWER: R vs H");
        System.out.printf("  recall@10: H=%s  R=%s%n", fmt(h10, N), fmt(r10, N));
        System.out.printf("  recall@20: H=%s  R=%s%n", fmt(h20, N), fmt(r20, N));
        if (r20 >= h20 && r10 >= h10) {
            System.out.println("  VERDICT: R MATCHES OR BEATS H — hybrid pre-filter is unnecessary complexity.");
        } else if (h20 > r20 || h10 > r10) {
            System.out.println("  VERDICT: H BEATS R — hybrid anchors/BM25 rescue cases that Cohere misses on full corpus.");
        } else {
            System.out.println("  VERDICT: MIXED — check per-target table.");
        }
        System.out.printf("%n  R extrapolation @ 1254 obligations:%n");
        System.out.printf("    estimated cost: $%.4f total ($%.5f/obl)%n", extrapR, totalCostR);
        System.out.printf("    estimated time @ p50=%dms: ~%d minutes sequential%n", p50R, extrapLatR_p50_min);
        System.out.printf("    (parallelisable — Cohere semaphore=10, realistic wall-clock << sequential)%n");
        System.out.println("=".repeat(110));
    }

    private static String fmt(long hit, int total) {
        return String.format("%d/%d=%.3f", hit, total, (double) hit / total);
    }

    // -----------------------------------------------------------------------
    // PART A: Label sanity check
    // -----------------------------------------------------------------------

    private void printLabelSanityCheck(List<ObligationRow> obligations, List<ControlRow> controls) {
        // Build control map for fast lookup
        Map<String, ControlRow> ctrlById = new HashMap<>();
        for (ControlRow c : controls) ctrlById.put(c.id(), c);

        // The 5 persistent-miss pairs
        String[][] pairs = {
            { "OBL-9195d73555c491951f2ee7fb",  "CTRL-6bdbc18912465617d0efc705" },
            { "OBL-776467908fceed61e954b941",   "CTRL-5b9e54346cd02aa555372a31" },
            { "OBL-91a78a63215663af021440df",   "CTRL-e1165408c4e89d4295beb8f3" },
            { "OBL-6f6a5ef6e7a1ba875efe97ea",   "CTRL-e89d3570412151cb8f244c4d" },
            { "OBL-c8dba974babb09898e45497b",   "CTRL-49b721e43cb8446bca872656" },
        };

        // Build obligation map
        Map<String, ObligationRow> oblById = new HashMap<>();
        for (ObligationRow o : obligations) oblById.put(o.id(), o);

        System.out.println();
        System.out.println("=".repeat(140));
        System.out.println("  PART A — LABEL SANITY CHECK (5 persistent misses)");
        System.out.println("=".repeat(140));
        String[] assessments = {
            "QUESTIONABLE — obligation is a legal prohibition (acts violating freezing are void), " +
                "not a bank obligation to screen; Dow Jones screening is related but the match is loose.",
            "LIKELY WRONG — obligation: formalize EDD procedures in AML policy doc. " +
                "Control: sanctions hit 3-step escalation process. Different safeguards.",
            "GENUINELY HARD BUT CORRECT — obligation: identify/verify customer+rep+UBO before relationship. " +
                "Control: video biometric KYC is ONE mechanism of identity verification.",
            "GENUINELY HARD BUT CORRECT — obligation: identify role/position of intermediary acting for customer. " +
                "Control: collect ID docs for directors/reps/UBOs before account approval. Related but oblique.",
            "LIKELY WRONG — obligation: appoint company representative as AML responsible person. " +
                "Control: four-eyes review by Compliance Officer for risk score changes. Different governance.",
        };

        for (int i = 0; i < pairs.length; i++) {
            String oblId = pairs[i][0];
            String ctrlId = pairs[i][1];
            ObligationRow obl = oblById.get(oblId);
            ControlRow ctrl = ctrlById.get(ctrlId);
            System.out.printf("%n[%d] %s → %s%n", i + 1, oblId, ctrlId);
            System.out.println("  OBLIGATION:");
            System.out.printf("    subject    : %s%n", obl != null ? obl.subject() : "NOT FOUND");
            System.out.printf("    action     : %s%n", obl != null ? obl.action() : "NOT FOUND");
            if (obl != null && obl.conditions() != null && !obl.conditions().isEmpty()) {
                System.out.printf("    conditions : %s%n", String.join("; ", obl.conditions()));
            }
            System.out.println("  CONTROL:");
            System.out.printf("    description: %s%n", ctrl != null ? ctrl.description() : "NOT FOUND");
            System.out.printf("  ASSESSMENT: %s%n", assessments[i]);
        }
        System.out.println("=".repeat(140));
        System.out.println("  SUMMARY: 2 labels appear LIKELY WRONG (OBL-776..., OBL-c8d...)");
        System.out.println("           2 labels QUESTIONABLE/LOOSELY MATCHED (OBL-919..., OBL-6f6...)");
        System.out.println("           1 label GENUINELY HARD BUT CORRECT (OBL-91a...)");
        System.out.println("=".repeat(140));
    }

    // -----------------------------------------------------------------------
    // HyDE — call Haiku to generate a hypothesis control description
    // -----------------------------------------------------------------------

    /**
     * Calls Haiku with a HyDE prompt: given a regulatory obligation, generate
     * 1-2 sentences describing the internal bank control an examiner would expect.
     * Inline in the harness only — not productionized.
     *
     * @return the HyDE text, or null on error
     */
    private String callHyde(ObligationRow obl) {
        try {
            StringBuilder oblText = new StringBuilder();
            if (obl.subject()  != null) oblText.append("Subject: ").append(obl.subject()).append("\n");
            if (obl.action()   != null) oblText.append("Action: ").append(obl.action()).append("\n");
            if (obl.conditions() != null && !obl.conditions().isEmpty()) {
                oblText.append("Conditions: ").append(String.join("; ", obl.conditions())).append("\n");
            }
            if (obl.sourceText() != null && !obl.sourceText().isBlank()) {
                oblText.append("Source: ").append(obl.sourceText()).append("\n");
            }

            String requestJson = objectMapper.writeValueAsString(Map.of(
                "anthropic_version", "bedrock-2023-05-31",
                "max_tokens", 150,
                "temperature", 0,
                "messages", List.of(Map.of(
                    "role", "user",
                    "content", "Given this regulatory obligation, write 1-2 sentences describing the "
                        + "internal bank control an examiner would expect to satisfy it. "
                        + "Use operational language: name the mechanism, owner or team, and cadence if applicable. "
                        + "Do NOT restate the regulation; describe the bank control.\n\n"
                        + oblText.toString().trim()
                ))
            ));

            JsonNode resp = bedrockSvc.invokeModel(null, null, HYDE_MODEL_ID, requestJson);
            // Response shape: {"content":[{"type":"text","text":"..."}]}
            JsonNode content = resp.path("content");
            if (content.isArray() && !content.isEmpty()) {
                return content.get(0).path("text").asText(null);
            }
        } catch (Exception e) {
            System.err.printf("HyDE call failed for %s: %s%n", obl.id(), e.getMessage());
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Build control text map for a given config
    // -----------------------------------------------------------------------

    private Map<String, String> buildControlTexts(List<ControlRow> controls, EvalConfig cfg) {
        Map<String, String> texts = new HashMap<>(controls.size());
        for (ControlRow c : controls) {
            texts.put(c.id(), buildControlCard(c, cfg));
        }
        return texts;
    }

    /**
     * Build the text representation of a control card.
     * Baseline: description only.
     * richCard: description + standards + owner + controlType/category (skipping null/blank parts).
     */
    private String buildControlCard(ControlRow c, EvalConfig cfg) {
        String desc = c.description() != null ? c.description().trim() : "";
        if (!cfg.richCard()) return desc;

        StringBuilder sb = new StringBuilder(desc);

        if (c.mappedStandards() != null && !c.mappedStandards().isEmpty()) {
            String standards = c.mappedStandards().stream()
                    .filter(s -> s != null && !s.isBlank())
                    .collect(Collectors.joining(", "));
            if (!standards.isBlank()) {
                sb.append(" | standards: ").append(standards);
            }
        }
        if (c.owner() != null && !c.owner().isBlank()) {
            sb.append(" | owner: ").append(c.owner().trim());
        }
        // controlType/category
        boolean hasType = c.controlType() != null && !c.controlType().isBlank();
        boolean hasCat  = c.category()    != null && !c.category().isBlank();
        if (hasType || hasCat) {
            sb.append(" | ");
            if (hasType) sb.append(c.controlType().trim());
            if (hasType && hasCat) sb.append("/");
            if (hasCat) sb.append(c.category().trim());
        }
        return sb.toString().trim();
    }

    // -----------------------------------------------------------------------
    // Anchor index: canonical → set of control IDs
    // -----------------------------------------------------------------------

    private Map<String, Set<String>> buildAnchorIndex(
            List<ControlRow> controls, Map<String, String> controlTexts) {
        Map<String, Set<String>> anchorToControls = new HashMap<>();

        for (String[] entry : ANCHOR_LEXICON) {
            String canonical = entry[0];
            Set<String> matched = new HashSet<>();
            for (ControlRow c : controls) {
                String text = controlTexts.getOrDefault(c.id(), "").toLowerCase(Locale.ROOT);
                for (int i = 1; i < entry.length; i++) {
                    if (text.contains(entry[i].toLowerCase(Locale.ROOT))) {
                        matched.add(c.id());
                        break;
                    }
                }
            }
            anchorToControls.put(canonical, matched);
        }
        return anchorToControls;
    }

    /**
     * Find anchor canonicals present in the obligation text.
     */
    private Set<String> anchorsForObligation(ObligationRow obl) {
        String text = buildObligationSearchText(obl).toLowerCase(Locale.ROOT);
        Set<String> found = new HashSet<>();
        for (String[] entry : ANCHOR_LEXICON) {
            String canonical = entry[0];
            for (int i = 1; i < entry.length; i++) {
                if (text.contains(entry[i].toLowerCase(Locale.ROOT))) {
                    found.add(canonical);
                    break;
                }
            }
        }
        return found;
    }

    /**
     * Full text of an obligation for anchor matching (subject + action + conditions).
     */
    private String buildObligationSearchText(ObligationRow obl) {
        StringBuilder sb = new StringBuilder();
        if (obl.subject()  != null) sb.append(obl.subject()).append(" ");
        if (obl.action()   != null) sb.append(obl.action()).append(" ");
        if (obl.conditions() != null) {
            for (String c : obl.conditions()) {
                if (c != null) sb.append(c).append(" ");
            }
        }
        if (obl.sourceText() != null) sb.append(obl.sourceText()).append(" ");
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // BM25 index — hand-rolled, no Lucene
    // -----------------------------------------------------------------------

    static class Bm25Index {
        private final Map<String, Map<String, Double>> tfByDoc;   // docId -> term -> tf
        private final Map<String, Double> idf;                     // term -> idf
        private final Map<String, Double> docLengths;             // docId -> length
        private final double avgDocLen;
        private final int N;

        Bm25Index(Map<String, String> docTexts) {
            this.N = docTexts.size();
            tfByDoc = new HashMap<>(N);
            idf = new HashMap<>();
            docLengths = new HashMap<>(N);

            // Build term frequencies per doc
            Map<String, Integer> df = new HashMap<>();
            double totalLen = 0;
            for (Map.Entry<String, String> e : docTexts.entrySet()) {
                String docId = e.getKey();
                List<String> tokens = tokenize(e.getValue());
                double len = tokens.size();
                totalLen += len;
                docLengths.put(docId, len);

                Map<String, Double> tf = new HashMap<>();
                for (String t : tokens) {
                    tf.merge(t, 1.0, Double::sum);
                }
                tfByDoc.put(docId, tf);
                for (String t : tf.keySet()) {
                    df.merge(t, 1, Integer::sum);
                }
            }
            avgDocLen = N == 0 ? 1.0 : totalLen / N;

            // IDF: log((N - df + 0.5) / (df + 0.5) + 1)
            for (Map.Entry<String, Integer> e : df.entrySet()) {
                double dfVal = e.getValue();
                idf.put(e.getKey(), Math.log((N - dfVal + 0.5) / (dfVal + 0.5) + 1.0));
            }
        }

        /** Score all documents for a query string; returns map docId → BM25 score. */
        Map<String, Double> score(String query) {
            List<String> qTokens = tokenize(query);
            Map<String, Double> scores = new HashMap<>(tfByDoc.size());
            for (Map.Entry<String, Map<String, Double>> docEntry : tfByDoc.entrySet()) {
                String docId = docEntry.getKey();
                Map<String, Double> tf = docEntry.getValue();
                double docLen = docLengths.getOrDefault(docId, avgDocLen);
                double score = 0.0;
                for (String t : qTokens) {
                    if (!idf.containsKey(t)) continue;
                    double f = tf.getOrDefault(t, 0.0);
                    double idfVal = idf.get(t);
                    double numerator = f * (BM25_K1 + 1.0);
                    double denominator = f + BM25_K1 * (1.0 - BM25_B + BM25_B * docLen / avgDocLen);
                    score += idfVal * (numerator / denominator);
                }
                if (score > 0) scores.put(docId, score);
            }
            return scores;
        }

        static List<String> tokenize(String text) {
            if (text == null || text.isBlank()) return List.of();
            String[] parts = text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
            List<String> tokens = new ArrayList<>();
            for (String p : parts) {
                if (p.length() >= 2 && !STOPWORDS.contains(p)) {
                    tokens.add(p);
                }
            }
            return tokens;
        }
    }

    // -----------------------------------------------------------------------
    // Candidate generation — unified method
    // -----------------------------------------------------------------------

    /**
     * Generate top-K candidate control IDs for an obligation, using the given config.
     *
     * <ul>
     *   <li>Always: dense retrieval via Titan embed cosine (top-denseK).</li>
     *   <li>hyde: additional dense retrieval using the HyDE hypothesis embedding (top-30).</li>
     *   <li>anchors: force-include controls whose card contains anchor phrases from obl.</li>
     *   <li>sparse: BM25 top-denseK union with dense.</li>
     *   <li>Blend: 0.6*dense_norm + 0.3*hyde_norm + 0.4*sparse_norm + 0.15*anchorBonus; dedup; take top-K.</li>
     * </ul>
     *
     * @param routeByOblId mutable map to record which route surfaced the expected control;
     *                     key = oblId + ":" + expectedCtrlId, value = set of route names.
     *                     May be null (skips route tracking).
     */
    private List<String> candidates(
            ObligationRow obl,
            EvalConfig cfg,
            int K,
            Map<String, float[]> controlVecs,
            String queryText,
            Bm25Index bm25,
            Map<String, Set<String>> anchorToControls,
            Map<String, String> controlTexts,
            float[] hydeVec,
            Map<String, Set<String>> routeByOblId) {

        // --- Dense scores (raw query) ---
        float[] qVec = embedSvc.embed(queryText);
        if (qVec == null) return List.of();

        Map<String, Double> denseScores = new HashMap<>(controlVecs.size());
        for (Map.Entry<String, float[]> e : controlVecs.entrySet()) {
            double sim = TitanEmbeddingService.cosine(qVec, e.getValue());
            denseScores.put(e.getKey(), sim);
        }

        int TOP_N = cfg.denseK();
        Set<String> candidateSet = topN(denseScores, TOP_N);
        // Track which candidates came from which route
        Map<String, Set<String>> ctrlRoutes = new HashMap<>();
        for (String id : candidateSet) addRoute(ctrlRoutes, id, "dense-raw");

        // --- HyDE dense scores ---
        Map<String, Double> hydeScores = new HashMap<>();
        if (cfg.hyde() && hydeVec != null) {
            for (Map.Entry<String, float[]> e : controlVecs.entrySet()) {
                double sim = TitanEmbeddingService.cosine(hydeVec, e.getValue());
                hydeScores.put(e.getKey(), sim);
            }
            Set<String> hydeTop = topN(hydeScores, 30);
            for (String id : hydeTop) {
                addRoute(ctrlRoutes, id, "dense-hyde");
                candidateSet.add(id);
            }
        }

        // --- Sparse (BM25) ---
        Map<String, Double> sparseScores = new HashMap<>();
        if (cfg.sparse() && bm25 != null) {
            sparseScores = bm25.score(queryText);
            Set<String> sparseTop = topN(sparseScores, TOP_N);
            for (String id : sparseTop) {
                addRoute(ctrlRoutes, id, "sparse");
                candidateSet.add(id);
            }
        }

        // --- Anchor forced includes ---
        Set<String> anchorForced = new HashSet<>();
        if (cfg.anchors()) {
            Set<String> oblAnchors = anchorsForObligation(obl);
            for (String anchor : oblAnchors) {
                Set<String> anchoredControls = anchorToControls.getOrDefault(anchor, Set.of());
                for (String id : anchoredControls) {
                    addRoute(ctrlRoutes, id, "anchor");
                    anchorForced.add(id);
                    candidateSet.add(id);
                }
            }
        }

        // --- Normalize and blend ---
        double denseMax = denseScores.values().stream().mapToDouble(Double::doubleValue).max().orElse(1.0);
        double hydeMax  = hydeScores.isEmpty() ? 1.0
                : hydeScores.values().stream().mapToDouble(Double::doubleValue).max().orElse(1.0);
        double sparseMax = sparseScores.isEmpty() ? 1.0
                : sparseScores.values().stream().mapToDouble(Double::doubleValue).max().orElse(1.0);
        if (denseMax  == 0.0) denseMax  = 1.0;
        if (hydeMax   == 0.0) hydeMax   = 1.0;
        if (sparseMax == 0.0) sparseMax = 1.0;

        final Map<String, Double> finalSparse = sparseScores;
        final Map<String, Double> finalHyde   = hydeScores;
        final double fDenseMax = denseMax, fHydeMax = hydeMax, fSparseMax = sparseMax;

        record Scored(String id, double score) {}
        List<Scored> ranked = new ArrayList<>(candidateSet.size());
        for (String ctrlId : candidateSet) {
            double dNorm = denseScores.getOrDefault(ctrlId, 0.0) / fDenseMax;
            double hNorm = finalHyde.getOrDefault(ctrlId, 0.0) / fHydeMax;
            double sNorm = finalSparse.getOrDefault(ctrlId, 0.0) / fSparseMax;
            double anchor = anchorForced.contains(ctrlId) ? 1.0 : 0.0;
            // Blend: 0.6*dense_raw + 0.3*hyde + 0.4*sparse + 0.15*anchor
            double blended = 0.6 * dNorm + 0.3 * hNorm + 0.4 * sNorm + 0.15 * anchor;
            ranked.add(new Scored(ctrlId, blended));
        }
        ranked.sort(Comparator.comparingDouble(Scored::score).reversed());

        List<String> result = ranked.stream().limit(K).map(Scored::id).toList();

        // Record routes for the expected control of this obligation (if tracking enabled)
        if (routeByOblId != null && obl.expectedControlId() != null && !obl.expectedControlId().isBlank()) {
            String key = obl.id() + ":" + obl.expectedControlId();
            Set<String> routes = ctrlRoutes.getOrDefault(obl.expectedControlId(), Set.of());
            routeByOblId.put(key, routes);
        }

        return result;
    }

    private static void addRoute(Map<String, Set<String>> ctrlRoutes, String ctrlId, String route) {
        ctrlRoutes.computeIfAbsent(ctrlId, k -> new LinkedHashSet<>()).add(route);
    }

    /** Return the top-N keys from a score map (descending), as a mutable LinkedHashSet. */
    private static Set<String> topN(Map<String, Double> scores, int n) {
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(n)
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Build the query text from an obligation and config.
     * Baseline: subject + " " + action.
     * richCard/anchors/sparse/hyde: also append conditions and sourceText.
     */
    private String buildQuery(ObligationRow obl, EvalConfig cfg) {
        String subject = obl.subject() != null ? obl.subject() : "";
        String action  = obl.action()  != null ? obl.action()  : "";
        if (!cfg.richCard() && !cfg.sparse() && !cfg.anchors() && !cfg.hyde()) {
            return (subject + " " + action).trim();
        }
        // Extended query: subject + action + conditions + sourceText
        return buildObligationSearchText(obl).trim();
    }

    // -----------------------------------------------------------------------
    // Fixture loading
    // -----------------------------------------------------------------------

    private List<ControlRow> loadControls() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/eval/controls.json")) {
            if (is == null) throw new IllegalStateException("Missing fixture: /eval/controls.json");
            JsonNode arr = objectMapper.readTree(is);
            List<ControlRow> rows = new ArrayList<>();
            for (JsonNode n : arr) {
                List<String> standards = new ArrayList<>();
                JsonNode stdNode = n.path("mappedStandards");
                if (stdNode.isArray()) {
                    for (JsonNode s : stdNode) standards.add(s.asText(null));
                }
                rows.add(new ControlRow(
                        n.path("id").asText(null),
                        n.path("description").asText(null),
                        standards,
                        n.path("owner").asText(null),
                        n.path("controlType").asText(null),
                        n.path("category").asText(null)
                ));
            }
            return rows;
        }
    }

    private List<ObligationRow> loadObligations() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/eval/obligations_labelled.json")) {
            if (is == null) throw new IllegalStateException("Missing fixture: /eval/obligations_labelled.json");
            JsonNode arr = objectMapper.readTree(is);
            List<ObligationRow> rows = new ArrayList<>();
            for (JsonNode n : arr) {
                String expCtrl = n.path("expected_control_id").isNull()
                        ? null : n.path("expected_control_id").asText(null);

                List<String> conditions = new ArrayList<>();
                JsonNode condNode = n.path("conditions");
                if (condNode.isArray()) {
                    for (JsonNode c : condNode) conditions.add(c.asText(null));
                }

                String sourceText = null;
                JsonNode sourceNode = n.path("source");
                if (sourceNode.isObject()) {
                    JsonNode stNode = sourceNode.path("sourceText");
                    if (!stNode.isNull() && !stNode.isMissingNode()) {
                        sourceText = stNode.asText(null);
                    }
                }

                rows.add(new ObligationRow(
                        n.path("id").asText(null),
                        n.path("subject").asText(null),
                        n.path("action").asText(null),
                        conditions,
                        n.path("label").asText(null),
                        expCtrl,
                        n.path("note").asText(null),
                        sourceText
                ));
            }
            return rows;
        }
    }

    // -----------------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------------

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String rankStr(Integer rank) {
        if (rank == null || rank == 0) return "MISS";
        return String.valueOf(rank);
    }
}
