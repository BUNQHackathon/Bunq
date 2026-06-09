# Service-layer review — 2026-06-09

Scope: `backend/src/main/java/com/bunq/javabackend/service/` — 58 files, ~9.2k lines.
Method: 5 parallel review passes (pipeline, AI/Bedrock/KB, chat/SSE/infra, launch/compliance, documents/search), top findings re-verified against source.

Items marked ✓ were confirmed in source line-by-line; others are credible reviewer findings not individually re-checked.

---

## 1. Verdict correctness — changes what a regulator/user sees

### 1.1 GREEN verdict is unreachable once any gap exists ✓
`NarrateStage.java:104-112`. `determineOverall` ends with `if (gaps.size() > 3) return "amber"; return "amber";` — both branches amber. One trivial low-risk gap makes a 99%-covered run AMBER; runs are effectively never green.
**Fix:** incorporate residual risk / severity — green when all gaps are low-risk and none are escalation-required.

### 1.2 Ground-check fails open: unverifiable mappings marked verified ✓
`GroundCheckStage.java:255-262`. When a single-item batch's Bedrock call fails, `applyResult(batch.get(0), true, ...)` marks it verified. A mapping whose check consistently errors (context overflow, refusal) silently passes the audit trail.
**Fix:** fail closed — mark "ground-check inconclusive — manual review required", emit a distinct SSE event, exclude from verified counts.

### 1.3 Sanctions screening fails open on sidecar outage ✓
`SanctionsScreenStage.java:85-99`. Sidecar down → `sidecarHits = List.of()`, pipeline completes with a clean verdict. A `sanctions.degraded` SSE event is emitted but nothing is persisted on the run — proof pack and stored verdict show the party as cleared.
**Fix:** FAILED/DEGRADED run state, or per-counterparty "unscreened" hits with status `review`.

### 1.4 Mapping-confidence threshold of 50 inflates "satisfied"
Flagged independently by three reviewers. `MapObligationsControlsStage.java:248` sets `GapStatus.satisfied` at confidence ≥ 50; `GapAnalyzeStage.java:70` excludes those obligations from gap analysis entirely. A 50/100 "partial alignment" suppresses gap generation. The MATCH_OBLIGATIONS_TO_CONTROLS prompt gives no score-calibration anchors (unlike SCORE_GAP), so LLM scores cluster around 50–70 — right at the threshold.
**Fix:** raise to ~75 and/or require `mappingType == direct` for `satisfied`; add calibration anchors (0–20 unrelated / 40–60 partial with material gaps / 80–100 fully addresses) to the prompt in `SystemPrompts.java`.

### 1.5 Prompt/schema mismatch loses control categories
`SystemPrompts.java:33-35` tells the model to classify `directive` controls, but the tool schema/enum only has `technical/organizational/procedural`; `valueOf` failures are swallowed and category becomes null. The prompt also conflates "type" (preventive/detective/corrective) with the code's `category` axis.
**Fix:** align prompt and schema; separate the two axes or drop one.

---

## 2. Data-integrity bugs

### 2.1 Parallel extract stages clobber each other's Document writes
`IngestStage.java:137-140` + both extract stages each do read → mutate → full `putItem` on the same Document while running in parallel; the second writer reverts the first's `obligationsExtracted`/`controlsExtracted`/`extractionS3Key` fields.
**Fix:** targeted `updateItem` per field (the repo already has this pattern in `touchLastUsed` / `updateUploadMetadata`).

### 2.2 Failed Transcribe permanently caches empty text
`IngestStage.java:168-171`: on transcription failure `text = ""` is written to S3 and the extraction key saved; every future run cache-hits the empty file forever.
**Fix:** don't persist the extraction key when extraction failed; leave it null so the next run retries.

### 2.3 Streaming cost metrics are always zero ✓
`BedrockStreamingService.java:190-209` reads `input_tokens`/`cache_*` from `message_delta.usage`, but those arrive in `message_start.message.usage` (message_delta carries only output_tokens). Every streaming call logs `cache_read=0 input=0` and under-reports SessionCostService.
**Fix:** handle the `message_start` event and emit its usage.

### 2.4 Audit-chain hashes aren't canonicalized
`AuditLogService.java:60-71`: payload maps serialized without sorted keys — logically identical payloads can hash differently, breaking chain verification with no tampering. Also: `sessionLocks` map grows unbounded (one lock per session, never removed); `Thread.sleep` in the retry loop swallows `InterruptedException` without restoring the flag.
**Fix:** sort keys (TreeMap or `SORT_PROPERTIES_ALPHABETICALLY`); evict locks; restore interrupt status.

### 2.5 Chat streaming retry duplicates output
`streamWithCachedSystem` uses `retryWhen` on the Flux; a mid-stream throttle retry re-emits all earlier deltas, and `ChatService.java:296-297` appends them again to `fullText` and re-sends over SSE — corrupted message on screen and in the saved ChatMessage.
Related: `RagService.java:162` discards the SDK's returned future — a pre-stream failure (auth, DNS) hangs the emitter for the full 5-minute timeout. Chain `.whenComplete` → `emitter.completeWithError`.

### 2.6 Dedup races and checksum edge cases
- `IngestStage.java:146` — key-only `remove()` on `inFlightExtractions` can evict a newer future; use the two-arg `remove(key, future)`. A session sharing a failed in-flight future aborts its whole pipeline instead of retrying per-session.
- `DocumentService.java:94` — `Base64.decode` throws on multipart composite checksums (`"<base64>-5"`); reject or strip the part suffix.
- `SessionDocumentsService.detach` — blind read-modify-write on Session while `attach` uses conditional updates; concurrent attach is silently lost.

---

## 3. Performance

### 3.1 Six stages run on `ForkJoinPool.commonPool()`
ExtractControls, GapAnalyze, Map, Filter, GroundCheck, Narrate call `CompletableFuture.runAsync(...)` without the executor argument (IngestStage correctly passes `stageWorkerExecutor`). GapAnalyze then `join()`s pipelineExecutor tasks from a commonPool thread — deadlock setup under load. One-line fixes.

### 3.2 Textract/Transcribe polling holds worker threads up to 15 min each
`Thread.sleep` loops inside `stageWorkerExecutor` tasks; 16 concurrent documents exhaust the pool and `CallerRunsPolicy` blocks the pipeline thread itself.
**Fix:** submit-then-scheduled-poll (or SNS notification channel for Textract).

### 3.3 Full-table scans on hot paths
- Sanctions local lookup scans the whole entity table per counterparty → GSI on `entity_name_normalized`.
- `ChatMessageRepository.findByChatId` + `listChats` scan → GSI on `chatId`.
- `JurisdictionOverviewService.overview/triage` — full scans + N+1 launch gets.
- `searchLaunches` is the only search method without a scan cap.
- `detachDocumentFromAll` scans all sessions.

### 3.4 N+1 DynamoDB patterns
- `LaunchService.mapRunsWithSummary` — ~4 sequential reads per run plus per-gap obligation gets; hundreds of calls per `GET /launch/{id}`. Batch + parallelize, or denormalize obligation source into Gap.
- Mapping-cache probe (`MapObligationsControlsStage.java:284-298`) — up to 20 individual `findById`s per obligation; BatchGetItem gives ~20× fewer calls on the hottest stage.
- Per-item `save()` loops in extract/filter/gap stages → BatchWriteItem (25/request).

### 3.5 ExtractControlsStage doesn't chunk
Full policy text in one Bedrock call (`ExtractControlsStage.java:141`); a large policy set overflows context or truncates silently. Apply the TextChunker + parallel pattern from ExtractObligations.

### 3.6 Streaming path bypasses the Bedrock semaphore
`BedrockStreamingService` has no concurrency guard; chat spikes can throttle the pipeline's sync calls that do respect the semaphore. Share or add a (smaller) streaming semaphore.

### 3.7 ProofPack/Report generation on the request thread
Sequential S3 downloads + whole ZIP in heap; large packs block a servlet thread for tens of seconds.
**Fix:** async-generate + presigned URL, or `StreamingResponseBody`.

---

## 4. Accuracy improvements

- **Grounding check is a 30-char prefix match** (`ExtractObligationsStage.java:284-289`, same in controls) — a snippet diverging after 30 chars passes. Use ≥100 chars plus token-containment ratio.
- **Narrative truncation invisible** — streaming `max_tokens` 4096 (sync uses 32768); NarrateStage summary capped at 512 tokens; neither path surfaces `stop_reason=max_tokens`.
- **Prompt injection** — retrieved chunk text, doc text, and user query interpolated into the XML prompt unescaped in both chat services; a malicious uploaded doc can break out of `<context>`. Escape `<>&`.
- **Hardcoded FAQ override in chat system prompt** forces canned answers even when the KB contradicts them — silent-staleness hazard. Move FAQs into the KB.
- **Checkpoint resume leaves PipelineContext empty** (unverified) — skipped stages never reload outputs into ctx; downstream stages reading `ctx.getRegulation()`/`ctx.getObligations()` directly misbehave on resume. Needs a test.
- **ProofPack cover PDF labels a truncated UUID as "SHA-256"** — misleading provenance in a regulator-facing artifact. Evidence rows carry real checksums; documents don't here.

---

## 5. Architecture suggestions

1. **Fail-closed policy for the verification chain.** Ground-check, sanctions, and relevance filtering all degrade silently. Persist a machine-readable degradation field on JurisdictionRun (which stages ran clean, which degraded) so AMBER-because-degraded is distinguishable from AMBER-because-gaps.
2. **Two-axis verdict computation.** Replace gap-count heuristics with severity × coverage aggregation — GapScorer's residual risk is already computed but unused by `determineOverall`.
3. **KB ingestion is fire-and-forget** — persist the ingestion job ID and poll to terminal status; until then search serves stale vectors and failures vanish.
4. **StaleRunSweeper** runs once at startup with an unconditional overwrite — make it `@Scheduled` with a conditional write so it can't kill a live run.
5. **GSIs over scans** — `status` on jurisdiction-runs, `chatId` on chat messages, `entity_name_normalized` on sanctions entities.

---

## 6. Lower-priority items (not detailed above)

Nova content-array corruption in `BedrockService.invokeNovaViaConverse` (`toString()` of array content); `Map.of` field-ordering nondeterminism in matcher requests; SSE timeout-callback emitter leak; `SessionCostService.accumulators` unbounded growth (use Caffeine TTL); `snapshotPerStage()` torn reads outside the lock; orphaned S3 objects (`transcribe-results/*`, stranded `documents/<hash>` on DynamoDB failure); `isModelAccessFailure` returns true for null messages; `CitationDTO.sha256` populated with document IDs that may be UUIDs; KB chunk IDs from 32-bit `String.hashCode`; `kbCandidates` O(n²) `List.contains` (use a Set); duplicate-upload re-triggers KB publish unnecessarily.

---

## Refuted during verification

- "Auto-provisioned runs produce false GREEN because `provisionJurisdiction` doesn't set regulation text" — wrong: `IngestStage.java:255-261` assembles `ctx.regulation` from attached regulation-kind documents.

## Suggested first wave

Sections 1.1–1.5 and 2.1–2.5 (verdict correctness + data integrity) — all small, surgical changes.
