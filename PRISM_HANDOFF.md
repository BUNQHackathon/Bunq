# Prism — Implementation Handoff

> **Context:** Two features to implement in priority order.  
> Feature A unblocks the bunq demo (manual document selection per jurisdiction).  
> Feature B makes the product-launch concept actually meaningful (relevance filtering).

---

## Feature A — Manual Document Override for Jurisdiction Runs

### Problem

`LaunchService.runJurisdiction` always delegates document selection to `AutoDocService.forJurisdiction(code)`, which picks docs purely by jurisdiction tag with zero product awareness. If the KB doesn't have the right regulations tagged for a jurisdiction, the session runs with no regulation docs → no obligations → no gaps → meaningless GREEN verdict.

### Goal

Allow the caller to pass explicit document IDs when triggering a jurisdiction run. If provided, use those. If omitted, fall back to AutoDocService (existing behavior unchanged).

### Definition of Done

`POST /launches/{id}/jurisdictions/{code}/run` with body `{"documentIds":["abc","def"]}` attaches exactly those documents to the created session and runs the pipeline against them. Running without a body still works identically to today.

---

### Changes

#### 1. New DTO — `RunJurisdictionRequestDTO.java`

Create at: `src/main/java/com/bunq/javabackend/dto/request/RunJurisdictionRequestDTO.java`

```java
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RunJurisdictionRequestDTO {
    private List<String> documentIds; // null or empty → autoDocService fallback
}
```

---

#### 2. `LaunchController.java` — accept optional request body

**File:** `src/main/java/com/bunq/javabackend/controller/launch/LaunchController.java`  
**Lines:** 76–82 (current `runJurisdiction` endpoint)

**Before:**
```java
@PostMapping("/{id}/jurisdictions/{code}/run")
public ResponseEntity<JurisdictionRunResponseDTO> runJurisdiction(
        @PathVariable String id,
        @PathVariable String code) {
    var run = launchService.runJurisdiction(id, code);
    return ResponseEntity.ok(LaunchMapper.toRunDto(run));
}
```

**After:**
```java
@PostMapping("/{id}/jurisdictions/{code}/run")
public ResponseEntity<JurisdictionRunResponseDTO> runJurisdiction(
        @PathVariable String id,
        @PathVariable String code,
        @RequestBody(required = false) RunJurisdictionRequestDTO body) {
    List<String> overrideDocIds = (body != null) ? body.getDocumentIds() : null;
    var run = launchService.runJurisdiction(id, code, overrideDocIds);
    return ResponseEntity.ok(LaunchMapper.toRunDto(run));
}
```

---

#### 3. `LaunchService.java` — thread override docs through

**File:** `src/main/java/com/bunq/javabackend/service/launch/LaunchService.java`

**Signature change** (line 335):
```java
// Before:
public JurisdictionRun runJurisdiction(String launchId, String code)

// After:
public JurisdictionRun runJurisdiction(String launchId, String code, List<String> overrideDocIds)
```

**Document selection block** (lines 351–354 area) — replace the autoDocService call:
```java
// Before:
List<Document> docs = autoDocService.forJurisdiction(code);
List<String> docIds = docs.stream().map(Document::getId).toList();

// After:
List<String> docIds;
if (overrideDocIds != null && !overrideDocIds.isEmpty()) {
    docIds = overrideDocIds;
} else {
    docIds = autoDocService.forJurisdiction(code)
                           .stream().map(Document::getId).toList();
}
```

**Also update the internal caller** — `rerunFailed` (line 396–407) calls `runJurisdiction(launchId, code)` without a third argument; pass `null` there to keep fallback behavior.

---

### What Does NOT Change

- `provisionJurisdiction` (called during `createLaunch`) — still uses AutoDocService; no override at creation time.
- AutoDocService itself — untouched.
- All existing launches with no body → identical behavior.

---

---

## Feature B — Product-Scoped Obligation Relevance Filtering

### Problem

Even after Feature A fixes document selection, the pipeline still extracts every obligation from every uploaded regulation and scores every single one for gaps. A credit card launch in Italy would surface 200+ obligations — most irrelevant to credit cards. The `Launch.brief` field exists and contains the product description but is never used during analysis.

### Goal

After obligations are extracted, score each one for relevance to the product brief. Drop obligations below a threshold before mapping and gap analysis. The result is a product-focused gap list, not a dump of every regulatory obligation in the uploaded documents.

### Definition of Done

1. A new `FILTER_OBLIGATIONS` stage runs after extraction and before mapping.
2. Each obligation gets a `relevanceScore` (0–1) and `relevanceReason`.
3. Obligations with `relevanceScore < 0.3` are excluded from mapping and gap analysis (but saved to DB with their score for auditability).
4. If `briefText` is absent (standalone session, no launch), the stage is a no-op and all obligations pass through.
5. The executive summary and jurisdiction run DTO report `obligationsExtracted` and `obligationsRelevant` counts.

---

### Data Model Changes

#### `Obligation.java`

**File:** `src/main/java/com/bunq/javabackend/model/obligation/Obligation.java`

Add two fields after `extractionConfidence` (line 68):

```java
private Double relevanceScore;    // 0.0–1.0; null if stage skipped
private String relevanceReason;   // LLM explanation; null if stage skipped
```

Both nullable — obligations from standalone sessions (no launch brief) will have `null` here, and the API treats `null` as "not filtered" (include in analysis).

No new DynamoDB index needed; these are plain attributes on the existing obligations table.

---

#### `PipelineStartRequestDTO.java`

**File:** `src/main/java/com/bunq/javabackend/dto/request/PipelineStartRequestDTO.java`

Add:
```java
private String briefText; // product brief from Launch.brief; null for standalone sessions
```

---

#### `PipelineStage` enum

Add `FILTER_OBLIGATIONS` between `EXTRACT_CONTROLS` and `MAP_OBLIGATIONS_CONTROLS` in the enum declaration. The exact file path: find the enum in `src/main/java/com/bunq/javabackend/model/enums/` or `service/pipeline/`.

---

### New File — `FilterObligationsStage.java`

**Create at:** `src/main/java/com/bunq/javabackend/service/pipeline/stage/FilterObligationsStage.java`

```java
@Component
public class FilterObligationsStage implements Stage {

    private static final double RELEVANCE_THRESHOLD = 0.3;

    private final BedrockService bedrockService;
    private final ObligationRepository obligationRepository;
    private final Executor pipelineExecutor;

    public FilterObligationsStage(BedrockService bedrockService,
                                  ObligationRepository obligationRepository,
                                  @Qualifier("pipelineExecutor") Executor pipelineExecutor) {
        this.bedrockService = bedrockService;
        this.obligationRepository = obligationRepository;
        this.pipelineExecutor = pipelineExecutor;
    }

    @Override
    public PipelineStage stage() {
        return PipelineStage.FILTER_OBLIGATIONS;
    }

    @Override
    public CompletableFuture<Void> execute(PipelineContext ctx) {
        String brief = ctx.getBriefText();
        if (brief == null || brief.isBlank()) {
            // No product brief → no-op; all obligations pass through unchanged
            return CompletableFuture.completedFuture(null);
        }

        List<Obligation> all = ctx.getObligations();

        List<CompletableFuture<Void>> futures = all.stream()
            .map(obl -> CompletableFuture.runAsync(() -> scoreAndTag(obl, brief, ctx), pipelineExecutor))
            .toList();

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenRun(() -> {
                List<Obligation> relevant = all.stream()
                    .filter(o -> o.getRelevanceScore() == null || o.getRelevanceScore() >= RELEVANCE_THRESHOLD)
                    .toList();

                // Replace obligations list in context with only relevant ones
                ctx.getObligations().clear();
                ctx.getObligations().addAll(relevant);

                int dropped = all.size() - relevant.size();
                if (dropped > 0) {
                    ctx.getSseEmitterService().emit(ctx.getSessionId(),
                        "obligations.filtered",
                        Map.of("total", all.size(), "relevant", relevant.size(), "dropped", dropped));
                }
            });
    }

    private void scoreAndTag(Obligation obl, String brief, PipelineContext ctx) {
        String prompt = """
            Product brief: %s

            Regulatory obligation:
            Subject: %s
            Action: %s
            Risk category: %s
            Deontic: %s

            Score how relevant this obligation is to the described product (0.0 = completely irrelevant, 1.0 = directly applies).
            """.formatted(brief,
                          obl.getSubject(),
                          obl.getAction(),
                          obl.getRiskCategory(),
                          obl.getDeontic());

        try {
            // Use Haiku — cheap, fast, good enough for relevance scoring
            JsonNode result = bedrockService.invokeWithTool(
                BedrockModel.HAIKU,
                SystemPrompts.RELEVANCE_SCORER,
                prompt,
                ToolDefinitions.SCORE_RELEVANCE_TOOL
            );

            double score = result.path("relevance_score").asDouble(1.0); // default: keep if parse fails
            String reason = result.path("relevance_reason").asText("");

            obl.setRelevanceScore(score);
            obl.setRelevanceReason(reason);
            obligationRepository.save(obl);

        } catch (Exception e) {
            // On Bedrock failure: keep obligation (don't drop on error)
            obl.setRelevanceScore(1.0);
            obligationRepository.save(obl);
        }
    }
}
```

**Design notes:**
- Parallel execution per obligation, same pattern as `GapAnalyzeStage.scoreGap`.
- On any Bedrock failure for a single obligation: assign score=1.0 (keep it). Never drop on error.
- If brief is absent: pure no-op, zero Bedrock calls.
- Saves relevanceScore to DB even for filtered obligations — auditable, can be reviewed later.

---

### New Prompt Constant — `SystemPrompts.java`

**File:** `src/main/java/com/bunq/javabackend/service/pipeline/prompts/SystemPrompts.java`

Add:
```java
public static final String RELEVANCE_SCORER =
    "You are a product compliance analyst. Given a product description and a regulatory obligation, " +
    "score how directly the obligation applies to this specific product. " +
    "Score 0.0 if it is entirely unrelated, 1.0 if it directly governs this product. " +
    "Be conservative: obligations that could plausibly apply score at least 0.4.";
```

---

### New Tool Definition — `ToolDefinitions.java`

**File:** `src/main/java/com/bunq/javabackend/service/ai/bedrock/ToolDefinitions.java`

Add alongside existing tool definitions:
```java
public static final String SCORE_RELEVANCE_TOOL = """
    {
      "name": "score_relevance",
      "description": "Score the relevance of a regulatory obligation to a product description.",
      "input_schema": {
        "type": "object",
        "required": ["relevance_score", "relevance_reason"],
        "properties": {
          "relevance_score": {
            "type": "number",
            "minimum": 0,
            "maximum": 1,
            "description": "0.0 = completely irrelevant, 1.0 = directly applies to this product"
          },
          "relevance_reason": {
            "type": "string",
            "description": "One sentence explaining why this score was given"
          }
        }
      }
    }
    """;
```

---

### `PipelineOrchestrator.java` — wire in the new stage

**File:** `src/main/java/com/bunq/javabackend/service/pipeline/PipelineOrchestrator.java`

**Constructor** — inject `FilterObligationsStage`:
```java
// Add to constructor parameters (alongside the other 8 stages):
private final FilterObligationsStage filterObligationsStage;
```

**Stage ordering** (lines 73–107 area) — insert after the parallel extract block completes, before the parallel sanctions+mapping block:

```java
// Existing parallel extract block:
CompletableFuture<Void> extractOblFuture  = runStageAsyncWithCheckpoint(ctx, extractObligationsStage);
CompletableFuture<Void> extractCtrlFuture = runStageAsyncWithCheckpoint(ctx, extractControlsStage);
CompletableFuture.allOf(extractOblFuture, extractCtrlFuture).join();

// NEW — insert here:
runStageWithCheckpoint(ctx, filterObligationsStage);  // no-op if no brief

// Existing parallel sanctions+mapping block continues:
CompletableFuture<Void> sanctionsFuture = runStageAsyncWithCheckpoint(ctx, sanctionsScreenStage);
CompletableFuture<Void> mappingFuture   = runStageAsyncWithCheckpoint(ctx, mapObligationsControlsStage);
CompletableFuture.allOf(sanctionsFuture, mappingFuture).join();
```

**PipelineContext construction** — pass the brief from the request:

Find where `PipelineContext` is constructed in `start()` and ensure `request.getBriefText()` flows into `ctx.briefText`. If `PipelineStartRequestDTO` doesn't yet have `briefText`, add it (see Data Model Changes above).

---

### `LaunchService.java` — pass the brief into the pipeline request

**File:** `src/main/java/com/bunq/javabackend/service/launch/LaunchService.java`

In `runJurisdiction` (line 335 area), when building the `PipelineStartRequestDTO`, fetch the launch and include its brief:

```java
Launch launch = launchRepository.findById(launchId)
    .orElseThrow(() -> new IllegalStateException("Launch not found: " + launchId));

PipelineStartRequestDTO pipelineReq = PipelineStartRequestDTO.builder()
    // ... existing fields ...
    .briefText(launch.getBrief())   // NEW
    .build();
```

---

### DTO changes — expose relevance data

#### `ObligationResponseDTO.java`

Add:
```java
private Double relevanceScore;
private String relevanceReason;
```

Map from `Obligation.relevanceScore` / `Obligation.relevanceReason` in `ObligationMapper`.

#### `JurisdictionRunResponseDTO.java`

Add:
```java
private Integer obligationsExtracted;  // total before filter
private Integer obligationsRelevant;   // after filter (null if no brief)
```

Populate in `LaunchService.mapRunsWithSummary` by counting obligations for the session's `currentSessionId`, split by whether `relevanceScore >= 0.3`.

---

### SSE Event

Add to the event vocabulary:

```
event: obligations.filtered
data: {"total": 87, "relevant": 23, "dropped": 64}
```

Emitted once by `FilterObligationsStage` after all scoring is complete.

---

### What Does NOT Change

- `ExtractObligationsStage` — unchanged; extracts everything as before.
- `GapAnalyzeStage` — unchanged; operates on whatever is in `ctx.getObligations()` (which is now the filtered list).
- `MapObligationsControlsStage` — unchanged; same reason.
- Standalone sessions (created via `POST /sessions` without a launch) — `briefText` is null → stage is a no-op → zero behavior change.
- All existing launches without a meaningful brief — brief is blank → no-op.

---

---

## Build Order

```
1. Feature A — RunJurisdictionRequestDTO            (new file, ~10 lines)
2. Feature A — LaunchController.runJurisdiction     (3-line change)
3. Feature A — LaunchService.runJurisdiction        (signature + 6-line conditional)
   ↳ Verify: POST /launches/{id}/jurisdictions/{code}/run with documentIds body
             attaches exactly those IDs to the session

4. Feature B — Obligation model fields              (2 new fields)
5. Feature B — PipelineStartRequestDTO.briefText    (1 new field)
6. Feature B — PipelineStage enum                   (1 new value)
7. Feature B — SystemPrompts.RELEVANCE_SCORER       (1 constant)
8. Feature B — ToolDefinitions.SCORE_RELEVANCE_TOOL (1 constant)
9. Feature B — FilterObligationsStage               (new file, ~80 lines)
10. Feature B — PipelineOrchestrator wiring          (inject + 1 line in stage sequence)
11. Feature B — LaunchService brief passthrough      (~4 lines)
12. Feature B — DTO + mapper updates                 (ObligationResponseDTO + JurisdictionRunResponseDTO)
    ↳ Verify: launch with brief "credit card" + Italy AML regulations
              produces obligationsRelevant < obligationsExtracted;
              gap list contains only credit-card-relevant obligations
```

---

## Risk Notes

- **FilterObligationsStage Bedrock cost:** ~0.25¢ per 100 obligations with Haiku. For 200 obligations: ~$0.005. Negligible.
- **Latency:** Parallel per obligation. 200 obligations with Haiku at ~500ms each, fully parallel = ~2–3s added to pipeline wall time.
- **Threshold 0.3 is a guess.** If it over-filters (too few relevant obligations), raise to 0.2. If it under-filters (too noisy), raise to 0.4. Consider making it a `@Value` property if demos show the need.
- **Feature A and B are independent.** A can ship and be demoed without B. B requires A's brief-passthrough groundwork to be useful.

---

---

## Feature C — Anti-Hallucination Prompts + Two-Phase Mapping (IMPLEMENTED 2026-05-28)

Already merged. Documented here so the next engineer knows what changed and doesn't redo it.

### What changed

1. **Reasoning-first tool schemas.** `extract_obligations.json`, `extract_controls.json`, `match_obligation_to_controls.json` got a `reasoning` field as the **first** property (and `required`); `source_text_snippet` moved to second in the extract schemas. `score_gap.json` got a leading `reasoning` field (not required). Rationale: `tool_choice:{"type":"any"}` forces an immediate tool call, so classic "think then answer" is impossible — instead the model generates the grounding/reasoning field *first* (tokens are produced top-to-bottom), which grounds the rest. This is the structured-output analog of Anthropic's "quote first" guidance.
   - **Safe because** parsers read fields by name (`node.path(...)`); extra fields are ignored. No parser changes needed.

2. **Anti-hallucination system prompts** (`SystemPrompts.java`): `EXTRACT_OBLIGATIONS` / `EXTRACT_CONTROLS` now carry a ground-only restriction ("use ONLY provided text, never invent article numbers/thresholds"), an explicit "return empty list if nothing concrete" permission, and 3-4 few-shot `<example>` blocks **including a negative example** (text that mentions a topic but states no duty → extract nothing). The negative example is the key lever against over-extraction.

3. **Two-phase mapping CoT** (`ObligationControlMatcher.java`): `match()` now (a) calls `invokeModel` without a tool for free-text `<analysis>` reasoning, then (b) calls `invokeModelWithTool` passing that analysis as `prior_analysis` for structured scoring. `match()` gained a `BedrockModel` parameter (call sites pass `BedrockModel.HAIKU` for now — see Decisions). Graceful fallback: if phase-1 throws, `prior_analysis=""` and phase-2 still produces the mapping. **Still per-obligation batched** (all candidate controls in one call), so this is 2 calls per obligation, not per pair.

4. **Eval harness** (`src/test/.../eval/PhaseEvalTest.java`, `@Disabled`): per-phase LLM accuracy + comparison across a prompt(OLD/NEW)×model(HAIKU/SONNET) matrix. Golden datasets in `src/test/resources/golden/`. `OldPrompts.java` snapshots pre-change prompt strings for OLD-vs-NEW comparison. Run manually (real Bedrock, costs money); flags `RUN_SONNET` / `RUN_OLD` trim the matrix.

---

## Cost & Performance — corrected against the actual code

The earlier email/LLM cost estimates assumed **(a)** mapping is one Bedrock call per (obligation, control) pair and **(b)** extraction runs on Sonnet. **Both are wrong for the current code.** Corrected reality:

- **Mapping is already batched per-obligation.** `MapObligationsControlsStage.processObligation` collects all uncached candidate controls (≤ `RERANK_TOP_N = 5`) and makes **one** `matcher.match()` call returning a list of scores. So ~100 obligations ≈ ~100 mapping units (×2 calls each after two-phase CoT ≈ ~200 calls), **not 800**.
- **Extraction runs on Haiku**, not Sonnet (`ExtractObligationsStage` / `ExtractControlsStage` use `BedrockModel.HAIKU`). Far cheaper than the $3 the email assumed.
- **top-K is already 5** (`RERANK_TOP_N = 5`), not 8.

### Realistic per-run estimate (1 jurisdiction, ~400 pages, ~100 obligations, mapping on Sonnet — see Decisions)

| Stage | Model | ~Cost |
|-------|-------|-------|
| Textract (400 pp) | — | $0.60 |
| Extract obligations | Haiku | ~$0.40 |
| Extract controls | Haiku | ~$0.50 |
| Mapping (two-phase, per-obligation batched) | Sonnet | ~$7–9 |
| Gap scoring | Haiku | ~$0.50 |
| Narrate | Haiku | ~$0.10 |
| Infra (DDB/S3/ECS) | — | ~$0.10 |
| **Total fresh run** | | **~$9–11** |
| **Crash retry, same session** | | **~$1–2** (mapping `findById` hits under the same sessionId) |
| **Re-run as a new session** | | **~$9–11 again** — no cross-session cache (IDs are session-scoped; see correction below) |

Mapping dominates because of the Sonnet decision + two-phase doubling. The two-phase CoT roughly doubles mapping vs single-pass, but per-obligation batching already keeps it from exploding.

**Note on "re-runs":** there is **no cheap cross-session re-run**. Extraction always runs the cold path and IDs fold in `sessionId`, so a *new* session on the same documents pays full price again. Only a crash-retry under the *same* sessionId benefits (extraction regenerates identical IDs → mapping cache hits). See the correction note in the optimization backlog below.

### Latency
With per-obligation batching and `BATCH_SIZE = 10` obligations processed in parallel, mapping wall time is bounded by Bedrock concurrency, not by call count. Main remaining wall-time cost is Textract on large docs (parallelize all Textract jobs up front if not already).

---

## Decisions (from cost discussion)

- **Mapping stays on Sonnet, not Haiku.** For a bank demo, fidelity on the semantic match step matters more than the ~$7 saving. **Action:** change the `match()` call sites in `MapObligationsControlsStage` (and `ChatWithGraphService`) from `BedrockModel.HAIKU` to `BedrockModel.SONNET`. (Currently HAIKU — the two-phase refactor preserved the existing model to avoid scope creep. This is the one-line flip.)
- **Tiered Haiku→Sonnet on mapping: rejected.** Considered (Haiku first pass, escalate ambiguous 30–70 to Sonnet) but rejected — don't want Haiku anywhere near mapping for a bank demo.

---

## Optimization Backlog — proposed vs. already-done

| Idea (from discussion) | Status in current code |
|------------------------|------------------------|
| Per-obligation batching of mapping | **Already done** — `processObligation` makes one call per obligation with all candidates |
| Reduce top-K candidates to 5 | **Already done** — `RERANK_TOP_N = 5` |
| **Multi-obligation batching** (N obligations + their candidates in ONE call) | **NOT done** — biggest remaining lever. Today `BATCH_SIZE=10` obligations run in *parallel* but as 10 separate calls. Grouping ~5 obligations per call would cut mapping calls ~5× more. Real win, but changes the matcher prompt + parsing. |
| Cheap-classifier / triage before extraction (skip recitals/definitions) | **Partially superseded** by Feature B (relevance filter), but that runs *after* extraction. A *pre*-extraction triage (NovaLite/regex on chunks) would cut Haiku extraction calls — separate, still open. |
| Cohort-level extraction cache (cross-session) | **NOT done — and deliberately blocked by current design.** See note below. |

> **⚠️ Correction (IDs are session-scoped by design — commit `4c3f824`).** An earlier draft of this doc claimed deterministic cross-session IDs and item-level cross-session mapping cache were "already done". **That is wrong for the current code.** `IdGenerator.obligationId(sessionId, documentId, subject, action)` / `controlId(sessionId, ...)` now **fold `sessionId` into the hash**, so the same document in two sessions yields *different* obligation/control IDs. The `cloneObligation`/`cloneControl` helpers and the `isObligationsExtracted` cache-hit path were **removed** — extraction always runs the cold path (always calls Bedrock).
> - **Consequence:** mapping IDs (`MAP-sha256(obligationId#controlId)`) are therefore also session-scoped. The `mappingRepository.findById` reuse in `processObligation` only hits **within the same session** (e.g. a retry after a crash that re-runs extraction under the *same* sessionId regenerates identical IDs). There is **no cross-session / cohort reuse**.
> - **Cohort cache options if ever pursued:** (1) a separate `chunk-hash → raw extraction fields` table (no session_id) that materialises fresh session-scoped rows on hit — the *only* option that respects the session-scoped invariant; (2) `findByDocumentId` + clone — **rejected**, it resurrects exactly the clone mechanism `4c3f824` intentionally removed. **Recommendation: skip for the demo** — the only saving is re-extracting the same docs (e.g. the 14 bunq policy docs reused across 3 jurisdictions ≈ ~$1), not worth new infra or reopening a closed design decision.

**Recommended next, in order:** (1) flip mapping to Sonnet ✅ done, (2) multi-obligation batching of mapping. That is the only remaining order-of-magnitude lever. (Cohort extraction cache is intentionally out of scope — see correction above.)

---

## Prompt Caching TTL — open issue (verify before trusting cost numbers)

`BedrockService.invokeModelWithTool` sets `cache_control: {"type":"ephemeral","ttl":"1h"}` on both the system prompt and the tools block (`BedrockService.java:466-490`, `400-416`). **The "1h" extended TTL almost certainly does not apply on Bedrock** — the code comment (lines 458-463) admits Bedrock exposes no `anthropic-beta: extended-cache-ttl-2025-04-11` header via `InvokeModelRequest`, so the field is sent as-is and likely falls back to the default 5-minute TTL.

- **Why it probably still works for the main case:** extraction fires ~all chunks in parallel within seconds, well inside a 5-minute window — first call creates the cache, the rest read it. 1h only matters for time-separated calls (across stages / sessions, e.g. a cohort cache).
- **The real risk:** if an invalid `ttl` value makes Bedrock reject the whole `cache_control` block, there's **no caching at all** — full prefix billed on every call. After Feature C the system prompt grew (few-shot examples), so the cached prefix is bigger → the downside of a dead cache is now larger.
- **How to check — don't guess:** the cost is already logged at `BedrockService.java:505-509`:
  ```
  Bedrock usage — cache_creation={} cache_read={} input={} output={}
  ```
  Run any small job, grep `Bedrock usage`. `cache_read > 0` on later calls → cache works (1h-vs-5min is secondary). Always `cache_read=0` with full `cache_creation` → cache is dead, fix it.
- **Safe fix if dead:** drop `"ttl":"1h"`, use bare `{"type":"ephemeral"}` (valid, default 5 min, covers the burst). Only pursue real 1h (for a cohort cache) by testing `"anthropic_beta":["extended-cache-ttl-2025-04-11"]` in the request *body* and confirming via the `cache_creation` metric.
