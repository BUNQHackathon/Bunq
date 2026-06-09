● Analysis complete — five parallel review agents covered all 60 service files, and I hand-verified the highest-impact claims against the source. The verdict-relevant headline: several compliance-critical paths "fail open" — infrastructure
errors silently produce compliant-looking results instead of flagging uncertainty. For a regtech product that's the pattern to fix first. Details below, grouped by impact; items marked ✓ I verified in the code myself.

1. Fail-open compliance paths (fix first)

Sanctions screening silently passes on error ✓ — SanctionsScreenStage.java:88-98: if the sidecar is unreachable, sidecarHits = List.of() and the pipeline proceeds to COMPLETE; only a transient SSE sanctions.degraded event is emitted, nothing
persisted. Local-table lookup failure (:143-146) also returns empty. A counterparty that was never actually screened is indistinguishable from a clean one in the final verdict. Fix: persist a SanctionHit with an UNKNOWN/ERROR status per
unscreened counterparty and force the verdict to reflect it (amber/blocked, not green).

Gap scoring degrades to "zero risk" on LLM failure ✓ — BedrockService.java:539-544 returns the entire response root when no tool_use block comes back (it logs a warning but execution continues). GapScorer then reads missing fields, nz()
defaults everything to 0.0, and the gap lands as minimal residual risk. A truncated or malformed model response literally lowers the reported risk. Fix: throw on missing tool_use, and treat an unscorable gap as "needs review", never as 0.0.

Hallucinated control IDs accepted — ObligationControlMatcher.java:97-101, 213: the control_id the model returns is never validated against the candidate list, so a hallucinated ID becomes a persisted mapping to a non-existent control.
One-line fix: filter results to known candidate IDs.

Verdict logic is flat — NarrateStage.determineOverall: any gap count ≥1 → "amber", regardless of residualRisk or escalation; a critical gap and a trivial one produce the same verdict color. Factor severity/escalation into red.

2. Accuracy issues

- EU regulation coverage gap — AutoDocService.EU_MEMBER_CODES hardcodes only NL/DE/FR/IE; other EU jurisdictions the app supports (AT, ES, IT appear in ProofPackService) never get MiCA/GDPR docs auto-loaded → silently missing obligations.
  Drive this from config or the DocJurisdiction table.
- Jurisdiction hardcoded to "EU" — KbRegulationService.java:57 labels every KB document EU, which feeds the graph and any jurisdiction filtering downstream.
- Grounding check too weak — ExtractObligationsStage.java:284-289 anchors on the first 30 normalized chars of the snippet; fabricated obligations with a plausible article prefix pass. Use a longer anchor or full-substring check.
- Relevance threshold dead zone — the prompt tells the model "plausibly applicable scores ≥0.4" while the code threshold is 0.3, so the threshold is effectively 0.4; and LaunchService counts relevanceScore == null as relevant, so obligations
  the filter never reached inflate the "relevant" count.
- Obligation ID collision ✓ (my finding) — IdGenerator.obligationId hashes only subject + action; two obligations differing only in condition/deontic collide into one ID and overwrite each other.
- Citations carry wrong SHA-256 — ChatService.toCitationDTO and ProofPackService.buildCoverPdf:185 both put a UUID where a content hash belongs — misleading for an auditor relying on the proof pack for integrity.
- Chat is stateless ✓ — ChatService.java:379 has a TODO: no history is sent, so every turn ignores prior conversation. Also the system prompt contains a hardcoded "CRITICAL EXCEPTION RULE" of 7 verbatim FAQ answers that overrides RAG —
  stale-answer factory; move FAQs out of the LLM path.

One claim from the agents I debunked ✓: the "mapping cache poisoning" finding. Obligation/control IDs are content-derived (IdGenerator), so text changes produce new IDs and the cached mapping correctly misses. But note BACKEND.md still
advertises "a pair mapped once is reused forever" while IdGenerator's comment says cross-session reuse is intentionally disabled — the doc and a claimed product differentiator are stale.

3. Reliability bugs

- SSE emitter races — SseEmitterService: complete() removes the session list from the map then iterates it, racing a concurrent register(); onTimeout doesn't remove the emitter the way onError does, so the heartbeat can keep hitting dead
  emitters.
- Audit chain fragility — AuditLogService: payload serialized from a plain HashMap (key order not guaranteed — re-verification of the hash chain can fail spuriously), and the per-session lock map grows forever. Canonicalize (sorted keys) and
  use a Caffeine cache for locks.
- Wrong failed-stage attribution — PipelineOrchestrator:153-157 unwraps CompletionException only one level, so a parallel-stage failure can be reported as INGEST in the SSE error event.
- Oversized plain-text docs become empty — IngestStage:203-250: >5MB text files get extractedText = "" but are still added, potentially setting the whole regulation context to empty with no user-visible signal.
- One bad doc poisons a ground-check batch — GroundCheckStage:176-195 marks all mappings in a batch failed when a single doc's S3 fetch fails.
- Streaming parser may drop events — BedrockStreamingService:126-149: after a successful parse the buffer is cleared rather than advanced, so a chunk containing two events loses the second (agent-reported; worth a unit test to confirm — per
  your preference, a mocked deterministic test fits here).

4. Speed / cost wins (rough order of payoff)

1. Sanctions local lookup is a full table scan per counterparty ✓ — SanctionsEntityRepository.findByNormalizedName scans with a filter expression. Add a GSI on entity_name_normalized → O(1) query. Biggest per-run win since it fires for every
   counterparty in every run.
2. Prompt-cache misses in the matcher — ObligationControlMatcher Phase-1 sends the system prompt as a plain string (no cache_control block), paying full input-token price on every per-obligation call. Also align the ttl between BedrockService
   and BedrockStreamingService. Direct Bedrock cost reduction.
3. Full table scans everywhere — SearchService (6 sequential scans per query), JurisdictionOverviewService, StaleRunSweeper (unfiltered findAll at startup), DocumentService.list. GSIs or server-side filter expressions; for search, longer-term
   OpenSearch.
4. N+1 DynamoDB reads — LaunchService.mapRunsWithSummary (3 reads per run), ProofPackService.buildCoverPdf (one getItem per gap), ExtractObligationsStage:86-95 (one getItem per doc to check kind). Batch-get instead.
5. Blocked platform threads — Textract/Transcribe pollers Thread.sleep in a loop for up to 10–15 min per job. Cheapest fix on Java 25: virtual threads for the pollers; cleaner architecture: SNS completion notifications.
6. Sequential LLM fan-out in graph chat — ChatWithGraphService:155-157 runs up to 5 matcher calls serially (~15s); parallelize like gap scoring already does. Also it bypasses the Reranker entirely while ChatService uses it — quality
   inconsistency for free.

1. Sanctions local lookup is a full table scan per counterparty ✓ — SanctionsEntityRepository.findByNormalizedName scans with a filter expression. Add a GSI on entity_name_normalized → O(1) query. Biggest per-run win since it fires for every
   counterparty in every run.
2. Prompt-cache misses in the matcher — ObligationControlMatcher Phase-1 sends the system prompt as a plain string (no cache_control block), paying full input-token price on every per-obligation call. Also align the ttl between BedrockService
   and BedrockStreamingService. Direct Bedrock cost reduction.
3. Full table scans everywhere — SearchService (6 sequential scans per query), JurisdictionOverviewService, StaleRunSweeper (unfiltered findAll at startup), DocumentService.list. GSIs or server-side filter expressions; for search, longer-term
   OpenSearch.
4. N+1 DynamoDB reads — LaunchService.mapRunsWithSummary (3 reads per run), ProofPackService.buildCoverPdf (one getItem per gap), ExtractObligationsStage:86-95 (one getItem per doc to check kind). Batch-get instead.
5. Blocked platform threads — Textract/Transcribe pollers Thread.sleep in a loop for up to 10–15 min per job. Cheapest fix on Java 25: virtual threads for the pollers; cleaner architecture: SNS completion notifications.
6. Sequential LLM fan-out in graph chat — ChatWithGraphService:155-157 runs up to 5 matcher calls serially (~15s); parallelize like gap scoring already does. Also it bypasses the Reranker entirely while ChatService uses it — quality
   inconsistency for free.

Architecture-level takeaways

1. Adopt a fail-closed convention for anything feeding the verdict. The sanctions, gap-scoring, and ground-check paths each independently invented "log + continue with empty/zero". A shared DegradedResult concept (persisted, surfaced in the
   verdict) would fix the class of bug rather than three instances.
2. Tighten the tool schemas — mapping_type, narrative, escalation_required aren't in required, so the model can legally omit fields the Java side then silently defaults (partial, false). Schema-level required is free accuracy.
3. Replace scan-based access patterns with GSIs before data grows — every scan above is invisible at demo scale and a cliff at production scale.

If you want, I can turn the top section (fail-open fixes) into a concrete implementation plan with per-step verifiable done-criteria and dispatch the subagents.

✻ Cogitated for 5m 14s
