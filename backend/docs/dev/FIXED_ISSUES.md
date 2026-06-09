# Fixed issues — service-layer review remediation (2026-06-09)

Consolidated from two review passes (`fixes.md` + `SERVICE_REVIEW_2026-06-09.md`, both now removed) over
`backend/src/main/java/com/bunq/javabackend/service/` (58 files, ~9.2k lines).
Remediation: 50 files changed (+1176/−523), `mvn test` green (32 tests), independent review pass over the
combined diff caught and fixed 4 integration regressions.

Status legend: ✅ fixed · ⏭ skipped (with reason) · 📌 deferred (architecture-level follow-up).

---

## 1. Fail-open compliance paths → fail-closed

| Status | Issue | Fix |
|---|---|---|
| ✅ | **Sanctions screening silently passed on sidecar outage** — `SanctionsScreenStage` set `sidecarHits = List.of()` and the run completed with a clean verdict; only a transient SSE event. | Synthetic `SanctionHit` with `matchStatus=under_review`, `listSource="screening unavailable"` persisted per unscreened counterparty; flows into ctx and the verdict (forces ≥ amber). |
| ✅ | **Ground-check failed open** — single-item batch failure called `applyResult(..., true, ...)`, marking unverifiable mappings VERIFIED. | `applyInconclusive`: `reviewerNotes="ground-check inconclusive — manual review required"`, distinct `ground_check.inconclusive` SSE event, excluded from verified counts. |
| ✅ | **One bad doc poisoned a ground-check batch** — single S3 fetch failure marked ALL mappings in the batch failed. | Per-doc fetch; fallback to `Document.extractedText` from DynamoDB on `NoSuchKey`; only mappings of the failed doc go inconclusive. |
| ✅ | **Gap scoring degraded to "zero risk" on LLM failure** — `BedrockService` returned response root when no `tool_use` block came back; `GapScorer.nz()` defaulted everything to 0.0 — malformed model output LOWERED reported risk. | `invokeModelWithTool` throws on missing `tool_use`; `GapScorer` throws `GapScoringException`; `GapAnalyzeStage` creates the gap with `escalationRequired=true`, `residualRisk=0.5`, "needs review" narrative. |
| ✅ | **Hallucinated control IDs accepted** — `ObligationControlMatcher` never validated model-returned `control_id` against the candidate list → mappings to non-existent controls. | Both single and batch paths filter to known candidate IDs, WARN on dropped IDs. |
| ✅ | **GREEN verdict structurally unreachable** — `NarrateStage.determineOverall` had `if (size > 3) return "amber"; return "amber";` — any gap ⇒ amber forever; a critical and a trivial gap produced the same color. | Severity-aware: red on `escalationRequired` or flagged sanction hit; amber on `residualRisk ≥ 0.4`, >3 gaps, or `under_review` sanctions; green otherwise. |

## 2. Accuracy

| Status | Issue | Fix |
|---|---|---|
| ✅ | **Mapping threshold 50 inflated "satisfied"** — confidence ≥ 50 ⇒ `GapStatus.satisfied` AND excluded from gap analysis; matching prompt had no score calibration, clustering scores right at the threshold. | Shared `SATISFIED_CONFIDENCE_THRESHOLD = 75` used by both `MapObligationsControlsStage` and `GapAnalyzeStage`; calibration anchors (0-20/40-60/80-100) added to MATCH prompt. |
| ✅ | **Prompt/schema mismatch lost control categories** — prompt instructed `directive` type; enum/schema only had technical/organizational/procedural; `valueOf` failures swallowed → null category. Type vs category axes conflated. | Prompt now names exactly the schema enums for both axes. |
| ✅ | **Grounding check trivially bypassable** — 30-char normalized prefix match. | Prefix extended to min(len, 100); fallback: ≥80% of tokens (len>3) must appear in source. Both extract stages. |
| ✅ | **Relevance threshold dead zone** — prompt said "≥0.4 plausibly applicable", code threshold 0.3; `LaunchService` duplicated the 0.3 literal. | `RELEVANCE_THRESHOLD = 0.4`, promoted to `public static final`, referenced from `LaunchService`. |
| ✅ | **Obligation ID collision** — `IdGenerator.obligationId` hashed only subject+action; obligations differing in condition/deontic overwrote each other. | Hash input extended with deontic + first condition. |
| ✅ | **EU regulation coverage gap** — `AutoDocService.EU_MEMBER_CODES` hardcoded NL/DE/FR/IE only → other EU jurisdictions silently missed MiCA/GDPR docs. | Full EU-27 list. |
| ✅ | **Jurisdiction hardcoded to "EU"** in `KbRegulationService` for every KB doc. | `guessJurisdiction` from doc key (FCA→UK, FINMA→CH, SEC/CFPB→US, EU regs→EU; fallback EU). Per-doc metadata unreachable from ListObjectsV2 — noted. |
| ✅ | **Chat was stateless** — TODO at the message-build site; every turn ignored prior conversation. Also: hardcoded "CRITICAL EXCEPTION RULE" with 7 verbatim FAQ answers overrode RAG context (stale-answer factory). | History loaded via GSI query (last ~20, role-deduped), current message excluded (post-review fix); FAQ block removed. |
| ✅ | **Prompt injection via unescaped content** — retrieved chunk text, doc text, and user query interpolated raw into the XML prompt in both chat services. | `xmlText()` escaping (`& < >`) applied in both. |
| ✅ | **Citations carried fake SHA-256** — `documentId` (possibly UUID) written into `sha256` fields; proof-pack cover PDF labeled a truncated ID "SHA-256". | sha256 set only when value matches `[0-9a-f]{64}`; cover PDF shows the full content hash or omits the line. |
| ✅ | **Tool schemas under-constrained** — `mapping_type`, `narrative`, `escalation_required` not in `required`; model could legally omit fields Java silently defaulted. | Added to `required` in match/score_gap schemas (ground-check schemas were already correct). |
| ✅ | **Narrative truncation invisible** — NarrateStage max_tokens 512, streaming 4096; no stop_reason check anywhere. | 2048 / 16384; WARN on `stop_reason=max_tokens` in both paths. |
| ✅ | **`obligationsRelevant` counter semantics** — null-score obligations counted as relevant; "filter ran" inferred from score presence. | Threshold now the shared constant; null-score semantics kept (no Session field records filter opt-out) — noted as residual. |
| ⏭ | **Mapping-cache cross-session reuse** — BACKEND.md advertises "a pair mapped once is reused forever" while IdGenerator intentionally disables cross-session reuse. | Doc/product claim mismatch — needs a product decision, not a code fix. |

## 3. Data integrity / reliability

| Status | Issue | Fix |
|---|---|---|
| ✅ | **Parallel stages clobbered Document writes** — Ingest/ExtractObligations/ExtractControls each did read→mutate→full `putItem` on the same Document concurrently; second writer reverted the first's flags. | Targeted `updateItem` methods on `DocumentRepository` (`updateExtractionResult`, `markObligationsExtracted`, `markControlsExtracted`), `ignoreNulls`, conditional on existence. |
| ✅ | **Failed extraction permanently cached empty text** — Transcribe failure wrote `""` to S3 and persisted the key; every later run cache-hit empty text forever. Same for >5MB text files. | No S3 write / no key on failure. Regulation docs: stage fails (fail-closed). Peripheral docs (brief/policy): WARN + `document.skipped` SSE + skip (post-review fix — one bad brief no longer kills the run). |
| ✅ | **In-flight dedup races** — key-only `remove()` could evict a newer future; a session sharing a failed future aborted its pipeline. | Two-arg `remove(docId, future)`. |
| ✅ | **Streaming cost metrics always zero** — input/cache tokens read from `message_delta.usage`; they arrive in `message_start.message.usage`. | `message_start` branch added; `message_delta` reads output_tokens only. |
| ✅ | **Streaming parser dropped events** — buffer `setLength(0)` after parse discarded a second event in the same chunk. Confirmed real. | Buffer advanced past consumed bytes via JsonParser offset; regression test `BedrockStreamingServiceBufferTest` added. |
| ✅ | **Mid-stream retry duplicated chat output** — `retryWhen` re-emitted all prior deltas; `fullText` and the client both doubled. | Retry only before first emission (per-subscription emitted flag). |
| ✅ | **RagService emitter hang** — SDK future discarded; pre-stream failures left the SseEmitter hanging 300s. | `.whenComplete` → `emitter.completeWithError`. |
| ✅ | **Audit chain fragility** — payload hashed from unordered map serialization (spurious chain-verification failures); `sessionLocks` leaked forever; `Thread.sleep` swallowed interrupts. | Recursive TreeMap canonicalization; `remove(sessionId, lock)` when uncontended; interrupt flag restored, retries aborted. |
| ✅ | **SSE emitter leak on timeout** — removal only registered in onCompletion/onError. | Removal registered directly in `onTimeout` (collection already CopyOnWriteArrayList — iterate race not present). |
| ✅ | **SessionCostService races + unbounded growth** — `snapshotPerStage` read `long[]` without the writers' lock; accumulator map never evicted. | Synchronized snapshot; `lastTouched` + hourly `@Scheduled` eviction (idle >6h). |
| ✅ | **StaleRunSweeper** — ran once at startup, unconditional save could flip a freshly-restarted RUNNING run to FAILED. | `@Scheduled(fixedDelay=5m)` + re-read-before-flip guard; `@EnableScheduling` added to the application class. |
| ✅ | **Wrong failed-stage attribution** — orchestrator unwrapped `CompletionException` only one level; parallel-stage failures reported as INGEST. | Full cause-chain walk for `PipelineStageException`. |
| ✅ | **Multipart checksum crash** — `Base64.decode` threw on composite checksums (`"<base64>-N"`) in `DocumentService.finalize`. | Rejected with a clear error (composite ≠ content SHA-256, so dedup-by-hash would be wrong). |
| ✅ | **Blind session detach** — read-modify-write `putItem` raced the conditional `attachDocument`. | Conditional `REMOVE document_ids[idx]` with retry; second-conflict case handled (post-review fix). |
| ✅ | **Stranded S3 objects** — copy-then-save failure leaked `documents/<hash>`; transcribe-results JSON never deleted. | Cleanup on non-conditional failure; best-effort delete after transcript read. |
| ✅ | **Redundant KB publish on duplicate upload** — every dedup hit re-fired ingestion. | Publish only when kind/jurisdictions changed. |
| ✅ | **KB ingestion fire-and-forget** — job started, never tracked; failures invisible, search served stale vectors. | Background poll (15s, ≤10min) on a virtual thread; ERROR on FAILED; ConflictException → one delayed retry. |
| ✅ | **isModelAccessFailure(null) == true** — null-message exceptions falsely triggered the model fallback. | Null → false + log. |

## 4. Performance / cost

| Status | Issue | Fix |
|---|---|---|
| ✅ | **Six stages ran on ForkJoinPool.commonPool** (Map/Gap/Filter/GroundCheck/Narrate/ExtractControls) — incl. join-from-commonPool deadlock setup. | Executor passed everywhere; NarrateStage converted to explicit constructor for `@Qualifier`. |
| ✅ | **Textract/Transcribe pollers blocked the 16-thread pool up to 15 min/job.** | `stageWorkerExecutor` → virtual threads; poll intervals 30s. Bedrock concurrency still capped by the existing semaphore. |
| ✅ | **Sanctions lookup = full table scan per counterparty** (biggest per-run win). | GSI `entity-name-normalized-index` (Terraform + model annotation + query). |
| ✅ | **Chat messages = full table scan** (`findByChatId`, `listChats`). | GSI `chat_id-timestamp-index`, ascending query. ECS env refs to both tables updated (post-review fix). |
| ✅ | **Matcher phase-1 prompt-cache miss** — system prompt sent as plain string, full input price per obligation. | System block array with `cache_control: ephemeral`; per-obligation content stays in the user message. TTL field mismatch between sync/streaming also removed. |
| ✅ | **N+1 DynamoDB reads** — `mapRunsWithSummary` (~4 reads/run + per-gap obligation gets), extract-stage doc-kind checks. | Per-run fan-out on the async executor; obligations map reused for gap sources; doc kinds taken from `ctx.getIngestedDocuments()`. |
| ✅ | **ExtractControlsStage sent full policy text in one call** — context overflow on large policies. | TextChunker + parallel per-chunk calls + ID dedup (mirrors obligations stage). |
| ✅ | **Streaming bypassed the Bedrock semaphore.** | Dedicated `Semaphore(min(8, maxConcurrent))`, released in `doFinally`. |
| ✅ | **ChatWithGraphService: ~5 serial matcher calls (~15s) + no reranker** while ChatService reranks. | Parallelized; Reranker wired into both services (it was injected-but-unused in ChatService). |
| ✅ | **kbCandidates O(n²)** — `ArrayList.contains` in a double loop. | LinkedHashSet. |
| ✅ | **searchLaunches unbounded scan** — only search path without `scanCap`. | `LaunchRepository.findAll(limit)`. |
| ✅ | **UNKNOWN runs vanished from triage**; `worstVerdict` ignored UNKNOWN. | UNKNOWN → pending bucket; worstVerdict returns "UNKNOWN" when nothing concrete. |
| ✅ | **Swallowed provisioning failures** — failed jurisdiction provisioning left a Launch with missing runs and HTTP 200. | FAILED `JurisdictionRun` persisted with `lastError`. |
| ✅ | **Unsanitized LLM narrative in report PDF.** | `GapNarrative.clean` + 5000-char cap. |
| ⏭ | **BatchWriteItem for obligations/controls/gaps** — per-item PutItem loops. | Skipped: no batch methods on the repositories; needs `DynamoDbEnhancedClient.batchWriteItem` plumbing. |
| ⏭ | **BatchGetItem for the mapping-cache probe** (up to 20 GetItems/obligation). | Skipped: `MappingRepository` has no batch-get. |
| ⏭ | **Evidence checksum cross-check at finalize.** | Skipped: finalize DTO carries no expected hash; S3 enforces the checksum at presigned-PUT time. DTO change required. |

## 5. Deferred (architecture follow-ups)

- 📌 **Async proof-pack generation** — `ProofPackService.generate` still downloads evidence and builds the ZIP on the request thread; move to background + presigned URL or `StreamingResponseBody`.
- 📌 **SNS completion notifications for Textract/Transcribe** instead of polling (virtual threads removed the urgency).
- 📌 **Checkpoint-resume context reload** — skipped stages don't repopulate `PipelineContext`; downstream stages reading `ctx.getRegulation()`/`ctx.getObligations()` directly may misbehave on resume. Needs a design pass + test.
- 📌 **OpenSearch for SearchService** — scans are now capped, but search remains scan-based.
- 📌 **Shared `DegradedResult` concept** — sanctions/ground-check/gap-scoring now individually fail closed; a persisted machine-readable degradation field on `JurisdictionRun` would make "amber because degraded" distinguishable from "amber because gaps" in the API.
- 📌 **BACKEND.md** still advertises cross-session mapping reuse that IdGenerator intentionally disables — doc or product needs reconciling.

## Deployment notes

- `terraform apply` required: two new standalone tables with GSIs (`sanctions_entities`, `chat_messages`) extracted from the `for_each` set; `moved` blocks preserve state, ECS env references updated.
- Refuted findings (no action): "auto-provisioned runs produce false GREEN" (IngestStage assembles `ctx.regulation` from attached docs); "mapping cache poisoning" (IDs are content-derived).
