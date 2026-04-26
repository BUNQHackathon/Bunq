# PRISM — Full Project Context

**Repo root:** `D:\Programs\Java\Java Project\Bunq`
**Compiled:** 2026-04-26
**Audience:** any future agent or contributor that needs full context on the codebase, infra, data, and product without re-exploring everything.

This file is the single source of truth for what the project is, how it is built, what it deploys, and what its data looks like in production today.

---

## 0. TL;DR

- **Product:** Prism (deployed under the brand "LaunchLens" — `main.d3per36itdzm5e.amplifyapp.com`). A compliance copilot for neobanks. Two flows:
  1. **Launches** — a product brief × jurisdictions → multi-stage pipeline → RED/AMBER/GREEN verdict + cited proof pack.
  2. **Home Jurisdictions** — country-level overview of which existing launches are compliant / need changes / are blocked.
  3. **Ask (Chat)** — RAG Q&A over 1,200+ regulation PDFs with cited sources and live compliance graph context.
- **Backend:** Java 25, Spring Boot 4.0.5, Maven, AWS SDK v2. Runs on **ECS Express Mode (Fargate)** in `eu-central-1`. Container image built with **Jib** (no Docker daemon).
- **Sidecar:** Python FastAPI — sanctions screening (OpenSanctions + DynamoDB fallback), proof-tree DAG, compliance-map DAG.
- **Inference:** AWS **Bedrock** Claude **Opus 4.7 / Sonnet 4.6 / Haiku 4.5** via `eu.anthropic.*` cross-region inference profiles, plus Amazon Nova Pro/Lite as fallback. Three **Bedrock Knowledge Bases** (regulations / policies / controls) on **S3 Vectors** (1024-dim, cosine).
- **Persistence:** DynamoDB on-demand, one table per entity (14 tables, all `launchlens-*`). S3 for documents + extractions + reports + KB sources. Secrets Manager for tokens and keys.
- **Frontend:** React 19 + TypeScript + Vite + Tailwind + shadcn-style hand-built components. **globe.gl + three.js** for the 3D world map; **D3** force-directed graph for the compliance visualization; **react-pdf** for the inline PDF viewer. Hosted on **AWS Amplify**.
- **Crawler:** Python with Playwright (FATF/Cloudflare bypass) and BeautifulSoup. Pulls 1,217 regulation PDFs from EUR-Lex, EBA, ECB, ESMA, FATF, CBI, Irish Statute Book.
- **Infra-as-code:** Terraform (≥ 1.9, AWS provider ~> 6.23) — local state for v1, all resources tagged `Project=launchlens, ManagedBy=terraform`.
- **Verdict math:** computed deterministically in Java from LLM-emitted dimensions. The LLM never decides the colour.
- **Audit:** every mapping decision is appended to a **chained SHA-256 audit log** in DynamoDB. `prev_hash`/`entry_hash` make the chain tamper-evident. The proof-pack ZIP carries this chain as `audit_trail.json`.
- **Citations:** first-class. Bedrock Citations API + custom typed citation events keep sources rendering before the answer text.

---

## 1. Project Narrative & Product Spec

### 1.1 Team & origin
Mikhail Zhemchuzhnikov, Mikhail Krestin, Andrew Kalinenko, Leonid Margulis. Built in a 17-hour hackathon sprint. Pitch hook: Revolut's Storonsky on cross-jurisdictional compliance being the #1 expansion bottleneck — every neobank in Europe has the same problem.

### 1.2 Problem statement
Neobank market entry is gated by 1–3 months of legal/compliance review per jurisdiction. The work is *obligation-mapping*, not text search: you have to know which regulations apply, map them to the controls already in place, score the gaps, and produce an audit-ready verdict. Doing this manually for 50+ features × 6+ jurisdictions does not scale. Prism automates the loop end-to-end with cited evidence.

### 1.3 Two main UX flows

**(a) Launches — product brief → multi-jurisdiction verdict pipeline.**
User submits:
- a free-text brief (`brief`)
- a kind: `PRODUCT | POLICY | PROCESS`
- target jurisdictions (NL, DE, FR, GB, US, IE in the demo)
- an optional license tag (e.g. `EMI`)

Backend creates one `Launch` row + one `JurisdictionRun` per code. Each run kicks off the async pipeline (`POST /launches/{id}/jurisdictions/{code}/run` → 202). Frontend opens an SSE stream to watch stage-by-stage progress; world map tints countries (running = grey pulse, GREEN/AMBER/RED on completion). Each country drawer shows: verdict pill, required-changes / blockers list, **Download Proof Pack** button (ZIP), and an inline compliance graph.

**(b) Home Jurisdictions — country-by-country triage of existing launches.**
3D globe heatmap of aggregated verdicts. Click a country → 3-column kanban (`keep` / `modify` / `drop`) of launches in that jurisdiction. Each card has graph icon (opens compliance map modal) and a proof-pack download. Cmd+J toggles back from a launch detail.

**(c) Ask — chat with grounded RAG.**
Streaming chat across the 3 Bedrock KBs (regulations, policies, controls). Citations stream as a typed `chat_citations` SSE event so the Sources block renders *before* the answer; `graph_refs` stream as well so the user gets clickable chips into the compliance graph.

### 1.4 Verdict computation (RED/AMBER/GREEN)
- LLM emits *dimensions* per gap: severity, likelihood, detectability, blast_radius, recoverability — each 0..1.
- `residual_risk = 0.4·severity + 0.25·likelihood + 0.15·detectability + 0.10·blastRadius + 0.10·recoverability` (deterministic, in `GapScorer`).
- Aggregation in Java (NarrateStage / JurisdictionRunService):
  - **RED** = any gap with `escalation_required = true`, OR sanction hit, OR mandatory regulation with zero coverage.
  - **AMBER** = remaining gaps with feasible remediation.
  - **GREEN** = all obligations covered, no sanction hits, all remaining gaps below threshold.
- Locked rule: the LLM *never* picks the colour — that decision is reproducible Java arithmetic.

### 1.5 Citations as a first-class primitive
- 3 KBs on S3 Vectors → top-K retrieve per question (5/KB, 10 merged).
- Bedrock Citations API returns inline markers; `ChatService` parses both citation APIs and re-emits them as a typed `chat_citations` event up-front, then streams the text deltas afterwards.
- Proof Pack ZIP includes:
  - `cover.pdf` — verdict summary + run metadata + counts + policy versions + unresolved gaps (one-line per gap with `regulation art_or_section score=X.XX`).
  - `gaps.pdf` — full gap list, longer-form narrative.
  - `sanctions.pdf` — counterparties screened + matches (or empty if N/A).
  - `mappings.xlsx` — obligation × control matrix with confidences, mapping_type, gap_status.
  - `audit_trail.json` — full hash-chained audit log for the run.

### 1.6 Performance wins claimed
20× pipeline speedup vs. v1, achieved by:
- Parallel KB retrieval across the 3 KBs.
- Batched obligation→controls matching (10 per LLM call).
- Tightened prompt cache prefixes (tools loaded once, byte-identical across calls).
- Parallel DynamoDB reads in the proof-pack stage.

### 1.7 Stack tags (devpost-style, corrected against the actual codebase)

**Removed (these were in the earlier hackathon write-up but are not actually used):**
- `api-gateway` — not used. The ALB is auto-provisioned by ECS Express Mode.
- `aws-lambda` — not used. Backend and sidecar both run on Fargate.
- `shadcn/ui` — not a dependency. Frontend components are hand-built on Tailwind.

**Actual stack:**

AWS
- amazon-web-services
- aws-bedrock
- aws-bedrock-knowledge-bases
- aws-s3-vectors
- aws-ecs (express-mode / fargate)
- aws-alb (auto-provisioned by ECS Express)
- aws-s3
- aws-dynamodb
- aws-textract
- aws-transcribe
- aws-secrets-manager
- aws-ecr
- aws-cloudwatch
- aws-cloudfront (optional fallback)
- aws-amplify (frontend hosting)
- aws-iam

AI / inference
- anthropic-claude-api (Opus 4.7, Sonnet 4.6, Haiku 4.5 via `eu.anthropic.*` cross-region inference profiles)
- amazon-nova (Pro + Lite — fallback model chain and batch ground-check via Converse API)
- amazon-titan-embeddings-v2 (1024-dim, used by all 3 KBs)

Backend
- java (25)
- spring-boot (4.0.5)
- maven
- aws-sdk-v2
- jib (containerless image build, no Docker daemon)
- openpdf (report generation)
- apache-poi (xlsx mappings export)
- lombok
- jackson-3
- springdoc-openapi

Sidecar & data ingest
- python
- fastapi
- uvicorn
- httpx
- playwright (FATF Cloudflare bypass)
- beautifulsoup
- openpyxl

Frontend
- react (19)
- typescript
- vite
- tailwind-css
- globe.gl
- three.js
- d3
- react-pdf
- react-router
- react-markdown

Infra & ops
- terraform
- docker-buildx (sidecar image only)
- github

### 1.8 Promo video reference
youtu.be/iTh7qKEBVeo — walkthrough of Launches tab (form → world map → country drawer → proof-pack & graph), Home Jurisdictions tab (3D globe → search → 3-column triage → graph modal), built-in PDF viewer, Ask page.

### 1.9 Stretch / next
More jurisdictions (DE, FR, NL deeper), image+table extraction from PDFs, voice via Polly, real-time alerts on regulation changes, broader v4 coverage architecture (denormalised `doc_jurisdictions` table, deterministic 100% coverage per jurisdiction).

---

## 2. Backend — Controllers & API Surface

All routes mounted under `/api/v1` (`server.servlet.context-path` in `application.yaml`). Default port 8080.

### 2.1 Endpoint table (every controller, every route)

| METHOD | PATH | Controller.method | Request DTO | Response | Notes |
|---|---|---|---|---|---|
| **AUTH** |
| POST | `/auth/check` | `AuthController.check` | — | `AuthCheckResponseDTO {valid}` | Bearer token validation no-op. |
| **CHAT (RAG)** |
| POST | `/chat` | `ChatController.startChat` | `ChatRequestDTO` | SSE `SseEmitter` | Streams citations + deltas, can link `chatId` to `sessionId`. |
| GET  | `/chat?limit=N` | `ChatController.listChats` | — | `List<ChatSummaryResponseDTO>` | Default limit 100, max 500. |
| GET  | `/chat/{chatId}/history` | `ChatController.history` | — | `ChatHistoryResponseDTO` | Up to `chat.history-limit=50` messages. |
| POST | `/chat/with-graph` | `ChatWithGraphController.chatWithGraph` | `ChatWithGraphRequestDTO` | SSE | Extended chat with compliance-graph context. |
| **RAG (non-streaming + streaming)** |
| POST | `/rag/query` | `RagController.query` | `RagRequest` | `RagResponse` | Non-streaming retrieval. |
| POST | `/rag/query/stream` | `RagController.queryStream` | `RagRequest` | SSE | Token-level streaming. |
| **DOCUMENTS (user library, dedup)** |
| POST | `/documents/presign` | `DocumentsController.presign` | `DocumentPresignRequest {filename, contentType}` | `DocumentPresignResponse {incomingKey, uploadUrl, expiresInSeconds=900}` | S3 PUT presign with SHA-256 checksum requirement. |
| POST | `/documents/finalize` | `DocumentsController.finalize` | `DocumentFinalizeRequest {incomingKey, filename, contentType, kind}` | `DocumentFinalizeResponse {document, deduped}` | Reads SHA-256 via S3 HeadObject + ChecksumMode, dedupes. |
| GET  | `/documents?kind=&limit=50` | `DocumentsController.list` | — | `DocumentListResponse` | `kind-last-used-at-index` GSI; omits `extractedText`. |
| GET  | `/documents/{id}` | `DocumentsController.get` | — | `DocumentResponseDTO` | Includes `extractedText` (can be 5–10 MB). |
| POST | `/sessions/{sessionId}/documents/{documentId}` | `SessionDocumentsController.attachDocument` | — | `SessionDocumentsResponse` | Idempotent attach; touches `lastUsedAt`. |
| DELETE | `/sessions/{sessionId}/documents/{documentId}` | `SessionDocumentsController.detachDocument` | — | 204 | Detach only — never deletes the Document row. |
| **EVIDENCE** |
| POST | `/sessions/{sessionId}/evidence/presign` | `EvidenceController.presign` | `EvidencePresignRequest` | `EvidencePresignResponse` | PUT presign carrying `checksumSHA256`. |
| POST | `/sessions/{sessionId}/evidence/finalize` | `EvidenceController.finalize` | `EvidenceFinalizeRequest` | `EvidenceResponseDTO` | 201; `sha256` read from S3 metadata. |
| GET  | `/evidence/{id}` | `EvidenceController.get` | — | `EvidenceResponseDTO` | |
| GET  | `/sessions/{id}/compliance-map` | `EvidenceController.getComplianceMap` | — | `GraphDAG` | Proxied from sidecar. |
| GET  | `/proof-tree/{mappingId}` | `EvidenceController.getProofTree` | — | `GraphDAG` | Proxied from sidecar. |
| **KNOWLEDGE BASE BROWSER** |
| GET  | `/kb/regulations` | `KbRegulationsController.list` | — | `List<KbRegulationSummaryDTO>` | Read-only KB inventory. |
| GET  | `/kb/regulations/{id}` | `KbRegulationsController.get` | — | `KbRegulationDetailDTO` | Includes presigned URL + excerpts. |
| **PER-STAGE DEBUG ENDPOINTS** |
| POST | `/obligations/extract` | `ObligationController.extract` | `ExtractObligationsRequestDTO` | 202 | |
| GET  | `/sessions/{id}/obligations` | `ObligationController.list` | — | `List<ObligationResponseDTO>` | |
| GET  | `/obligations/{id}` | `ObligationController.getById` | — | `ObligationResponseDTO` | |
| POST | `/controls/extract` | `ControlController.extract` | `ExtractControlsRequestDTO` | 202 | |
| GET  | `/sessions/{id}/controls` | `ControlController.list` | — | `List<ControlResponseDTO>` | |
| GET  | `/controls/{id}` | `ControlController.getById` | — | `ControlResponseDTO` | |
| POST | `/mappings/compute` | `MappingController.compute` | `ComputeMappingsRequestDTO` | 202 | |
| GET  | `/sessions/{id}/mappings` | `MappingController.list` | — | `List<MappingResponseDTO>` | |
| POST | `/gaps/score` | `GapController.score` | `ScoreGapsRequestDTO` | 202 | |
| GET  | `/gaps/list?sessionId=` | `GapController.list` | — | `List<GapResponseDTO>` | |
| POST | `/sanctions/screen` | `SanctionsController.screen` | `ScreenSanctionsRequestDTO` | 202 | |
| GET  | `/sessions/{id}/sanctions` | `SanctionsController.list` | — | `List<SanctionHitResponseDTO>` | |
| **GRAPH** |
| GET  | `/graph` | `GraphController.get` | — | `GraphDataDTO` | Full KB-wide compliance graph. |
| **JURISDICTIONS** |
| GET  | `/jurisdictions` | `JurisdictionsOverviewController.overview` | — | `List<JurisdictionOverviewDTO>` | Aggregate verdict per country. |
| GET  | `/jurisdictions/catalog` | `JurisdictionsOverviewController.catalog` | — | `List<Jurisdiction>` | Static catalog of 26 jurisdictions: NLD, DEU, FRA, ESP, ITA, IRL, BEL, LUX, AUT, POL, NOR, SWE, DNK, GBR, CHE, USA, TUR, SGP, ARE, SAU, RUS, BLR, IRN, PRK, SYR, CUB. Each with flag, supported licenses, regulators. |
| GET  | `/jurisdictions/{code}/triage?readOnly=` | `JurisdictionsOverviewController.triage` | — | `JurisdictionTriageDTO` | 3-column kanban: keep / modify / drop. |
| **LAUNCHES (multi-jurisdiction campaigns)** |
| POST | `/launches` | `LaunchController.createLaunch` | `CreateLaunchRequestDTO` | `LaunchResponseDTO` (201) | Creates Launch + N JurisdictionRuns. |
| GET  | `/launches` | `LaunchController.listLaunches` | — | `List<LaunchSummaryDTO>` | |
| GET  | `/launches/{id}` | `LaunchController.getLaunch` | — | `LaunchResponseDTO` | |
| DELETE | `/launches/{id}` | `LaunchController.deleteLaunch` | — | 204 | Judges only. |
| POST | `/launches/{id}/rerun-failed` | `LaunchController.rerunFailed` | — | `List<JurisdictionRunResponseDTO>` (202) | |
| POST | `/launches/{id}/jurisdictions/{code}/run` | `LaunchController.runJurisdiction` | — | `JurisdictionRunResponseDTO` (202) | Kicks off pipeline for one jurisdiction. |
| GET  | `/launches/{id}/auto-docs?j=CODE` | `LaunchController.autoDocs` | — | `List<DocumentResponseDTO>` | Auto-selected doc set for that jurisdiction. |
| GET  | `/launches/{id}/jurisdictions/{code}/proof-pack` | `LaunchController.getProofPack` | — | `byte[]` (application/zip) | The ZIP described in §1.5. |
| GET  | `/launches/{launchId}/jurisdictions/{code}/stream` | `LaunchController.jurisdictionStream` | — | SSE | Forwards the active session's pipeline events. |
| GET  | `/launches/{launchId}/jurisdictions/{code}/compliance-map` | `LaunchController.getComplianceMap` | — | `GraphDAG` | |
| **PIPELINE** |
| GET  | `/sessions/{id}/events` | `PipelineController.events` | — | SSE | Per-session event stream; 15s heartbeat. |
| **FILES (presigned helper)** |
| GET  | `/files/presigned-url?s3Uri=` | `FilesController.getPresignedUrl` | — | `PresignedUrlResponseDTO` | GET presign for KB S3 objects. |
| **SEARCH** |
| GET  | `/search?q=&limit=5` | `SearchController.search` | — | `SearchResponseDTO` | Full-text across regulations, obligations, controls, gaps, sanctions, launches, jurisdictions. |

### 2.2 Error handling
Two `@ControllerAdvice` classes:

- `web/GlobalExceptionHandler.java`
  - `MethodArgumentNotValidException` → **400** `{correlationId, validation_errors:[{field,message}]}`
  - `IllegalStateException` → **409** `{correlationId, message}`
- `controller/common/ErrorController.java`
  - `SessionNotFoundException` / `MappingNotFoundException` / `NotFoundException` → **404**
  - `EntityAlreadyExistsException` → **409**
  - `ForbiddenException` → **403**
  - `IllegalArgumentException`, `HttpMessageNotReadableException` → **400**
  - `ResponseStatusException` → declared status
  - `SidecarCommunicationException` → **502** (but pipeline often catches inside the stage and emits `sanctions.degraded` instead)
  - `RuntimeException` → **500** (`{message: "Unknown server error", correlationId}`)

Every response carries a `correlationId` injected via filter into MDC. Status codes used: 200, 201, 202, 204, 400, 401, 403, 404, 409, 500, 502.

### 2.3 CORS / Security
- `cors.allowed-origins` env: `https://*.amplifyapp.com,http://localhost:5173` (and 3000). Methods GET/POST/PUT/PATCH/DELETE/OPTIONS/HEAD; credentials true; exposes `ETag`; max-age 3600.
- `SecurityConfig` — stateless, CSRF off. Public: `OPTIONS, GET, HEAD`, plus `/swagger-ui/**`, `/v3/api-docs/**`, `/openapi/**`, `/actuator/health/**`, `/actuator/info`. Mutating verbs (`POST/PUT/PATCH/DELETE`) require **ROLE_ADMIN** via a custom `BearerTokenAuthFilter` reading `Authorization: Bearer <token>`. Token = `app.admin.token` env (`ADMIN_TOKEN`, demo value `demo-test-7f3a9b2c` for the deployed stack).

### 2.4 SSE event catalogue
All emitted by `SseEmitterService.send(sessionId, eventName, data)` as **native named events**.

Lifecycle:
- `connected`, `ping` (heartbeat 15s), `done`
- `stage.started` `{sessionId, ts, stage, ordinal, totalStages}`
- `stage.complete` `{sessionId, ts, stage, durationMs, itemsProduced}`
- `stage.skipped` `{stage, reason}`
- `stage.delta` (free-form per-stage delta)
- `stage.failed` `{sessionId, ts, stage, errorCode, message}`
- `pipeline.completed` `{sessionId, ts, summary, reportUrl}`
- `pipeline.failed`

Per-record:
- `document.extracted`, `document.cached`
- `obligation.extracted` (full ObligationDTO)
- `control.extracted`
- `mapping.computed` (with `metadata.route ∈ {llm, cached}`)
- `mapping.progress` `{processed, total, gapsSoFar}`
- `gap.identified` (full GapDTO with 5 dims + residual risk)
- `sanctions.hit`
- `sanctions.degraded` (sidecar unreachable, soft-fail)
- `ground_check.verified` / `ground_check.dropped`
- `narrative.completed` (ExecutiveSummaryDTO)
- `transcribe.polling`, `ingest.polling`

Chat:
- `chat.started`, `chat.delta`, `chat.citations`, `chat.completed`, `chat.failed`, `graph_refs`

---

## 3. Backend — Pipeline, Bedrock & Prompts

### 3.1 PipelineOrchestrator flow
File: `service/pipeline/PipelineOrchestrator.java`. State machine on `Session.state`:
```
CREATED → UPLOADING → EXTRACTING → MAPPING → SCORING → SANCTIONS → COMPLETE
                                                                    ↘ FAILED
```

Stages (enum `PipelineStage`):
1. **INGEST** (sync)
2. **EXTRACT_OBLIGATIONS** ⎫ run in parallel via `CompletableFuture.allOf`
3. **EXTRACT_CONTROLS**    ⎭
4. **SANCTIONS_SCREEN**    ⎫ parallel
5. **MAP_OBLIGATIONS_CONTROLS** ⎭
6. **GAP_ANALYZE** (sequential)
7. **GROUND_CHECK** (sequential)
8. **NARRATE** (sequential — produces PDF + executive summary)

Checkpoints: `Session.completedStages` is appended after each stage. `isCheckpointed()` lets the pipeline resume from the next unfinished stage on retry. Failures throw `PipelineStageException` carrying stage name + cause; orchestrator emits `stage.failed`, sets state `FAILED`, persists `JurisdictionRun.failedStage` and `lastError`.

After NARRATE: persist `JurisdictionRun {verdict, gapsCount, sanctionsHits, proofPackS3Key, lastRunAt}`, emit `pipeline.completed` with the presigned report URL, then call `sseEmitterService.complete(sessionId)`.

### 3.2 Stage-by-stage detail

**IngestStage.** Iterates `Session.documentIds`, dedupes concurrent extractions via `inFlightExtractions` ConcurrentHashMap. For each `Document`:
- If `extractedText` or `extractionS3Key` already set → cache hit, emit `document.cached` and reuse.
- Else: PDF → `TextractAsyncService.extractText()` (StartDocumentTextDetection + polling, S3-direct, **zero PDF bytes through the JVM**). Audio → `TranscribeAsyncService` with 15-min timeout, `transcribe.polling` events. Plain text fetched directly from S3 (5 MB guard).
- Result stored at `extractions/{docId}.txt`; `Document.extractionS3Key`, `pageCount`, `extractedAt` updated.
- Concatenated into `ctx.regulation`, `ctx.policy`, `ctx.briefText` by `kind`.

**ExtractObligationsStage.** Per regulation-kind document:
- If `Document.obligationsExtracted == true` → clone cached obligations from DB (per-document cache).
- Else: chunk text via `TextChunker`, parallel calls to Bedrock **HAIKU** with `SystemPrompts.EXTRACT_OBLIGATIONS` + `ToolDefinitions.EXTRACT_OBLIGATIONS_TOOL`. Tool emits an array of obligations with `{deontic ∈ [O,F,P], subject, action, conditions[], risk_category, severity, source_text_snippet, extraction_confidence, article, section, paragraph}`. Persist + emit `obligation.extracted`. Mark `obligationsExtracted=true` on the Document.

**ExtractControlsStage.** Same pattern over policy-kind docs. Tool: `extract_controls`. Output: `{control_type ∈ [technical, organizational, procedural], category ∈ [preventive, detective, corrective], description, evidence_type, implementation_status, source_text_snippet, extraction_confidence}`.

**SanctionsScreenStage.** For each counterparty in `ctx.counterparties`:
- Local table check first (`SanctionsEntityRepository.findByNormalizedName` — lowercase + alphanumeric-only + collapsed whitespace).
- Miss → call sidecar `POST /sanctions/screen`. Sidecar: OpenSanctions API (if enabled) + DynamoDB Jaro-Winkler fuzzy match (`fuzzy_threshold` default 0.92).
- Sidecar unreachable → emit `sanctions.degraded`, continue (non-fatal).
- `match_status`: `clear | flagged | under_review` based on best score (≥0.9 / ≥0.7).

**MapObligationsControlsStage.** The deterministic-id stage:
- For each obligation, candidate controls = `KnowledgeBaseService.retrieveControls(query, KB_TOP_K=5)` (semantic via Bedrock KB). Fallback: structural filter on `risk_category` / `mappedStandards`.
- Mapping ID = `MAP- + sha256(obligationId + "#" + controlId).substring(0,16)`. Look up by ID — hit means `metadata.route = cached`, miss means call Bedrock **HAIKU** (`match_obligation_to_controls` tool).
- Tool returns `{matches:[{control_id, match_score:0..100, mapping_type:[direct|partial|requires_multiple], reason}]}`.
- `gap_status = score >= 50 ? satisfied : partial`.
- Audit: `auditLogService.append(action="mapping_created", payload={obligation_id, control_id, evidence_sha256s, confidence})`.
- Batches of 10 obligations per Bedrock call; emits `mapping.progress` between batches.

**GapAnalyzeStage.** For obligations with no mapping or confidence < 50:
- Bedrock **HAIKU** with `score_gap` tool. Returns 5-dim residual risk + 4-dim legacy severity dimensions + `recommended_actions[]` + `narrative` + `escalation_required` boolean.
- `residual_risk = 0.4·severity + 0.25·likelihood + 0.15·detectability + 0.10·blast_radius + 0.10·recoverability`.
- Persist + emit `gap.identified`.

**GroundCheckStage.** For every mapping with a `semantic_reason`:
- Build batch (size 50): `{mapping_id, claim, source_text}`.
- Bedrock **NOVA_PRO** (Converse API) with `batch_ground_check` tool: each result `{mapping_id, verified: bool}`.
- Verified → audit `mapping_verified`. Unverified → `mapping.reviewerNotes = "ground-check failed: claim not found in source text"`, audit `mapping_ground_check_failed`, emit `ground_check.dropped`.

**NarrateStage.** Bedrock **HAIKU** with `SYSTEM_NARRATE_EXEC_SUMMARY` (no tool). Computes overall verdict from gap escalation flags + count, calls `ReportService.generate()` → **OpenPDF** 3-page report (cover + summary + narrative) → S3 `reports/{sessionId}.pdf` → 5-min presigned URL. Emits `narrative.completed` carrying `ExecutiveSummaryDTO {overall, gapCount, obligationCount, controlCount, topRisks, narrative}`.

### 3.3 Bedrock services

**BedrockService (sync).** Concurrency `bedrock.max-concurrent` (default 30, configured 15). Fallback chain on ThrottlingException: `HAIKU → SONNET → NOVA_PRO → NOVA_LITE`. Logs cache metrics: `cache_creation_input_tokens`, `cache_read_input_tokens`, `input_tokens`, `output_tokens`. For Anthropic models, request shape:
```json
{
  "anthropic_version": "bedrock-2023-05-31",
  "max_tokens": 4096,
  "system": [{"type":"text","text":"...","cache_control":{"type":"ephemeral"}}],
  "messages": [...],
  "tools": [/* byte-identical across calls */],
  "tool_choice": {"type":"any"}
}
```
For Nova: routes through Converse API (different SDK call).

**BedrockStreamingService (async).** `BedrockRuntimeAsyncClient.invokeModelWithResponseStream`. Emits `StreamingDelta {text, inputTokens, outputTokens, cacheReadTokens, cacheCreationTokens}`. Used by ChatService.

**Cache discipline.** Tool JSON loaded once at startup from `src/main/resources/prompts/tools/*.json`. Re-serialising on every call invalidates the cache prefix → don't.

### 3.4 BedrockModel enum
```
OPUS    eu.anthropic.claude-opus-4-7-v1:0              max 8192
SONNET  eu.anthropic.claude-sonnet-4-6-v1:0            max 4096
HAIKU   eu.anthropic.claude-haiku-4-5-20251001-v1:0    max 2048
NOVA_PRO  eu.amazon.nova-pro-v1:0                       max 5120
NOVA_LITE eu.amazon.nova-lite-v1:0                      max 5120
```
Stage routing today: extract / map / score-gap / narrate → HAIKU. Ground-check → NOVA_PRO (batch). Chat → SONNET.

### 3.5 Tool definitions (`src/main/resources/prompts/tools/`)

| File | Output shape |
|---|---|
| `extract_obligations.json` | `{obligations:[{deontic,subject,action,conditions[],risk_category,severity,source_text_snippet,extraction_confidence,article,section,paragraph}]}` |
| `extract_controls.json` | `{controls:[{control_type,category,description,evidence_type,implementation_status,source_text_snippet,extraction_confidence}]}` |
| `match_obligation_to_controls.json` | `{matches:[{control_id,match_score,mapping_type,reason}]}` |
| `score_gap.json` | `{severity_dimensions{regulatoryUrgency,penaltySeverity,probability,businessImpact},severity,likelihood,detectability,blast_radius,recoverability,recommended_actions[],narrative,escalation_required}` |
| `ground_check.json` | single `{verified}` |
| `batch_ground_check.json` | `{results:[{mapping_id, verified}]}` |
| `extract_counterparties_from_brief.json` | counterparty list for sanctions |

### 3.6 System prompts (`SystemPrompts.java` constants)
- `EXTRACT_OBLIGATIONS` — DDL deontic operators [O]/[F]/[P]; only emit groundable obligations.
- `EXTRACT_CONTROLS` — preventive/detective/corrective/directive classification.
- `MATCH_OBLIGATIONS_TO_CONTROLS` — score 0..100, type direct/partial/indirect/none.
- `SCORE_GAP` — 4 legacy + 5 residual dims, narrative, recommended actions.
- `GROUND_CHECK`, `GROUND_CHECK_BATCH` — verbatim/negligible-paraphrase verifier.
- `NARRATE_EXEC_SUMMARY` — 3-sentence non-jargon executive summary.
- `SYSTEM_CHAT_WITH_GRAPH` — assistant reasons over the assembled graph; references nodes by label.

### 3.7 Audit log mechanics
`AuditLogService.append(action, actor, sessionId, mappingId, payload)`:
1. `prevHash = repo.findLatestBySessionId(sessionId)?.entryHash ?? ""`
2. Canonical string (alphabetical field order): `action=…|actor=…|id=<uuid>|mappingId=…|payload=<json>|prevHash=…|sessionId=…|timestamp=<Instant.now()>`
3. `entryHash = SHA-256(canonical)`
4. `repo.saveIfNotExists(entry)` (conditional on `attribute_not_exists(id)`).

Real chain example from production (proof pack `audit_trail.json`):
```
ts: 2026-04-25T09:52:02.426488800Z
event: mapping_created
prev_hash: ""
entry_hash: d8e7de75235d05b01f463e4cf936969909abc07d074396a1b535d7bc6d43f945
actor: pipeline:map-obligations-controls
session_id: 08474979-ab00-4308-a788-aa7bd7fc36b0
mapping_id: MAP-285c839466c767c1
payload: {evidence_sha256s:[], control_id:"ctrl-d9b15ceb-...", obligation_id:"obl-1ea42133-...", confidence:45.0}
```
Subsequent entries chain via `prev_hash = previous entry_hash`. Tampering with any payload changes its hash and breaks every downstream entry.

### 3.8 Evidence hashing flow
`S3PresignHelper.presignEvidencePut(...)` requires SHA-256 from the client. After upload, `EvidenceService.hashFromS3(s3Key)` calls `headObject(... ChecksumMode.ENABLED)` and reads the **server-computed** `checksumSHA256`. **Zero evidence bytes pass through the JVM.** Stored on `Evidence.sha256` (base64 from S3). Audit-log payloads include `evidence_sha256s[]` for provenance.

---

## 4. Backend — Data Model & Persistence

### 4.1 Pattern (CODE_PATTERNS.md)
- Java 25, Lombok everywhere. Entities: `@DynamoDbBean @NoArgsConstructor @Setter @Builder @AllArgsConstructor` + `@Getter(onMethod_=@DynamoDbPartitionKey, @DynamoDbAttribute("..."))` on each getter.
- Services: `@Service @RequiredArgsConstructor @Slf4j`. Constructor injection only; never `@Autowired`.
- DTOs: `@Data @Builder @NoArgsConstructor @AllArgsConstructor`.
- **Jackson 3 only** (`tools.jackson.databind.*`). Spring Boot 4 ships it; using `com.fasterxml.jackson.*` crosses classloaders and fails silently.
- No `throws` on service/controller signatures — wrap into RuntimeExceptions.
- Presigns: always via `S3PresignHelper`; PUT presigns include `.checksumAlgorithm(SHA256)` so S3 computes server-side.

### 4.2 Tables (14 total, all `launchlens-*`, all on-demand billing)

| Table | PK / SK | GSIs | Purpose |
|---|---|---|---|
| `launchlens-sessions` | `id` | `launch-sessions-index` (launch_id, createdAt) | Pipeline session state. |
| `launchlens-documents` | `id` (= SHA-256 hex of file) | `kind-last-used-at-index` (kind, last_used_at) | Content-addressable document library. |
| `launchlens-obligations` | `id` | `session-id-index`, `document-id-index` | Extracted obligations. |
| `launchlens-controls` | `id` | `session-id-index`, `document-id-index` | Extracted controls. |
| `launchlens-mappings` | `id` (= deterministic `MAP-…`) | — | Obligation↔control matches. |
| `launchlens-gaps` | `id` | — | Scored gaps with 5-dim risk. |
| `launchlens-sanctions-hits` | `id` | — | Per-screen result. |
| `launchlens-sanctions-entities` | `id` (= `{list_source}#{list_entry_id}`) | — | Local sanctions cache (OFAC/EU/UN/UK). |
| `launchlens-evidence` | `id` | — | Evidence rows linked to mappings. |
| `launchlens-audit-log` | `id` (UUID) | `session_id-timestamp-index` (session_id, timestamp) | Hash-chained audit trail. |
| `launchlens-chat-messages` | `id` | — | Chat history (filter by `chatId`). |
| `launchlens-launches` | `id` | — | Top-level Launch object. |
| `launchlens-jurisdiction-runs` | `launch_id` / `jurisdiction_code` | `jurisdiction-index` (jurisdiction_code, launch_id) | One row per (launch, jurisdiction). |
| `launchlens-doc-jurisdictions` | `jurisdiction` / `document_id` | — | Denormalised: which docs apply to which jurisdiction (v4 coverage architecture). |

### 4.3 Entity field reference

**Document** — `id, filename, display_name?, content_type, size_bytes, s3_key, kind ∈ {regulation, policy, brief, evidence, audio, other}, jurisdictions:Set<String>, first_seen_at, last_used_at, extracted_text?, extraction_s3_key?, extracted_at?, page_count?, obligations_extracted:bool, controls_extracted:bool`.

**Session** — `id, state ∈ SessionState, regulation, policy, counterparties:List<String>, document_ids:List<String>, verdict?, errorMessage?, createdAt, updatedAt, launch_id?, jurisdiction_code?, executive_summary?, completed_stages:List<String>`.

**Obligation** — `id, source:ObligationSource{regulation, article, section, paragraph, source_text, retrieved_from_kb_chunk_id}, obligation_type, deontic ∈ {O,F,P}, subject, action, conditions:List<String>, risk_category, applicable_jurisdictions, applicable_entities, severity, regulatory_penalty_range, extracted_at, extraction_confidence:Double, session_id, regulation_id, document_id`.

**Control** — `id, control_type ∈ {technical, organizational, procedural}, category ∈ {preventive, detective, corrective}, description, owner, testing_cadence, evidence_type, last_tested:LocalDate, testing_status, implementation_status, mapped_standards:List<String>, linked_tools:List<String>, source_doc_ref:ControlSourceRef{bank, doc, section_id, kb_chunk_id}, session_id, bank_id, document_id`.

**Mapping** — `id (MAP-…), obligation_id, control_id, mapping_confidence:Double, mapping_type ∈ {direct, partial, requires_multiple}, gap_status ∈ {satisfied, gap, partial, under_review}, semantic_reason, structural_match_tags:List<String>, evidence_links:List<String>, reviewer_notes, last_reviewed:Instant, session_id, metadata:Map<String,String>` (commonly `{"route":"llm"|"cached"}`).

**Gap** — `id, obligation_id, gap_type ∈ {control_missing, control_weak, control_untested, control_expired}, gap_status, severity_dimensions:{regulatoryUrgency, penaltySeverity, probability, businessImpact, combinedRiskScore}, recommended_actions:List<{action, priority, effortDays, suggestedOwner}>, remediation_deadline:LocalDate, escalation_required:Boolean, narrative, session_id, severity, likelihood, detectability, blast_radius, recoverability, residual_risk:Double`.

**SanctionHit** — `id, session_id, counterparty:{name, country, type}, match_status ∈ {clear, flagged, under_review}, hits:List<{list_source, entity_name, aliases, match_score, list_version_timestamp}>, entity_metadata:Map<String,String>, screened_at`.

**Evidence** — `id, related_mapping_id, evidence_type, source, collected_at, evidence_url, sha256 (from S3), expires_at, confidence_score, human_reviewed, reviewer_id, review_timestamp, audit_trail:List<AuditTrailEntry>, session_id, s3_key, description, uploaded_at`.

**AuditLogEntry** — `id (UUID), session_id, mapping_id?, action, actor, timestamp, prev_hash, entry_hash, payload_json`.

**ChatMessage** — `id, chatId, sessionId?, role ∈ {USER, ASSISTANT}, content, citations:List<{kbType, chunkId, score, s3Uri, sourceText}>, timestamp, tokenUsage:{inputTokens, outputTokens, cacheReadTokens, cacheCreationTokens}`.

**Launch** — `id, name, brief, license?, kind ∈ {PRODUCT, POLICY, PROCESS}, counterparties:List<String>, status ∈ {CREATED, …}, created_at, updated_at`.

**JurisdictionRun** — PK `launch_id`, SK `jurisdiction_code`. Fields: `current_session_id, verdict ∈ {GREEN, AMBER, RED, PENDING, UNKNOWN}, gaps_count, sanctions_hits, proof_pack_s3_key, last_run_at, status, failed_stage?, last_error?`.

**DocJurisdictionItem** — PK `jurisdiction` (uppercase ISO-2), SK `document_id`. Fields: `kind, filename, last_used_at`.

### 4.4 Repositories — what they expose
- `SessionRepository`: `save, findById, deleteById, existsById, scanAll(limit), findByLaunchId` (GSI).
- `DocumentRepository`: `save, saveIfNotExists` (conditional on `attribute_not_exists(id)`), `findById, findByIds (BatchGet), findByKind (GSI, scanIndexForward=false), scanAll, touchLastUsed (UpdateItem ignoreNulls)`.
- `ObligationRepository` / `ControlRepository`: `save, findById, deleteById, findBySessionId (GSI), findByDocumentId (GSI), scanAll`.
- `MappingRepository`: `save, saveIfNotExists, findById, deleteById, findBySessionId (scan + filter)`.
- `GapRepository`, `SanctionHitRepository`, `EvidenceRepository`: standard CRUD + `findBySessionId` (scan).
- `AuditLogRepository`: `save, findById, deleteById, findBySessionId, findLatestBySessionId (GSI, limit 1, scanIndexForward=false), saveIfNotExists`.
- `ChatMessageRepository`: `save, findById, findByChatId, findAll`.
- `SanctionsEntityRepository`: `findByNormalizedName` (scan + filter, limit 50).
- `LaunchRepository`: `save, findById, findAll, deleteById`.
- `DocJurisdictionRepository`: `putAll (batch), findByJurisdiction (Query on PK), deleteAll (batch)`.
- `JurisdictionRunRepository`: `findByLaunchIdAndCode (composite GetItem), save, findByLaunchId (Query on PK), findByJurisdiction (GSI), findAll, delete`.

### 4.5 Mappers (`helper/mapper/`)
Static methods: `SessionMapper`, `DocumentMapper`, `ObligationMapper`, `ControlMapper`, `MappingMapper`, `GapMapper`, `SanctionHitMapper`, `EvidenceMapper`, `AuditLogMapper`, `ChatMessageMapper`, `LaunchMapper`. All stateless. Map DTO ↔ Model only — never expose Models on the API surface.

### 4.6 SSE payload DTOs (`dto/response/events/`)
`PipelineEvent` (abstract, methods `getSessionId()`, `getTimestamp()`, `getType()`). Subtypes: `StageStartedEvent`, `StageCompletedEvent`, `StageDeltaEvent`, `StageFailedEvent`, `PipelineCompletedEvent`, `ChatStartedEvent`, `ChatDeltaEvent`, `ChatCompletedEvent`, `ChatCitationsEvent`, `ChatFailedEvent`.

### 4.7 AWS bean wiring (`config/AwsConfig.java`)
- `S3Client` + `S3Presigner` (region from `aws.region`)
- `DynamoDbClient` + `DynamoDbEnhancedClient`
- `BedrockRuntimeClient` (sync, ApacheHttpClient, 8 retries, 600s timeout)
- `BedrockRuntimeAsyncClient` (Netty)
- `BedrockAgentRuntimeClient` + async (KB retrieval)
- `TextractClient`
- `TranscribeClient`
- `WebClient.Builder` for sidecar calls.

Health indicators: `DynamoHealthIndicator` (DescribeTable on sessions table), `S3HealthIndicator` (HeadBucket on uploads), `BedrockHealthIndicator` (bean presence), `SidecarHealthIndicator` (HTTP `/health`). Exposed at `/actuator/health/{dynamo|s3|bedrock|sidecar}`.

---

## 5. Python Sidecar & Regulatory Corpus Crawler

### 5.1 Sidecar (`backend/python-backend/`)
FastAPI 0.115 on uvicorn. Lifespan starts a shared `httpx.AsyncClient` (timeout 30 s). Routers: `health`, `sanctions`, `evidence`, `proof_tree`. Generic exception → `{error:"internal_error", code:500, message:str(exc)}`.

Auth: `require_bearer()` accepts `X-Sidecar-Token` (Java caller) **or** `Authorization: Bearer <token>`. Java's `SidecarClient` always sends the X-header. Token is the value of `SIDECAR_TOKEN` (auto-generated 32-char random in Secrets Manager).

Endpoints:
| METHOD | PATH | Purpose |
|---|---|---|
| GET | `/health` | `{status:"UP"}`, no auth. |
| POST | `/sanctions/screen` | `ScreenRequest{session_id, counterparties[], brief_text?}` → `{results:[SanctionHit]}`. Semaphore 20 parallel. |
| GET | `/proof-tree/{mapping_id}` | DAG: mapping → obligation+control → regulation_chunk + policy_chunk → evidence nodes. |
| GET | `/compliance-map/{session_id}` | DAG: obligations (green=mapped, red=gap) ←mapping→ controls. |
| POST | `/evidence/hash` | Multipart upload, returns `{sha256, size, contentType}`. **No longer used by Java** — kept for ad-hoc tools. |

Env: `SIDECAR_TOKEN`, `AWS_REGION` (default `eu-central-1`), `OPENSANCTIONS_API_KEY?`, `SANCTIONS_USE_LIVE_API` (default false), `FUZZY_THRESHOLD` (default 0.92), 9× `DYNAMODB_*_TABLE`.

### 5.2 Sanctions algorithm (`app/services/sanctions_screener.py`)
1. `normalize_name`: lowercase, strip punctuation + legal suffixes (`inc, llc, ltd, gmbh, s.a., s.a.r.l, plc, ag, co, corp, limited`), collapse whitespace.
2. If live API enabled: `POST {opensanctions_base}/match/default` with schema (Person/Company) + properties; track best score.
3. Local DynamoDB scan on `launchlens-sanctions-entities` (`entity_name_normalized` first-6-chars prefix, limit 100). Jaro-Winkler per candidate; keep ≥ threshold.
4. `match_status`: ≥0.9 → `flagged`, ≥0.7 → `under_review`, else `clear`.

### 5.3 DAG builder (`app/services/dag_builder.py`)
- `build_proof_tree(mapping_id)` reads mapping, fetches obligation + control + evidence in parallel, emits `mapping → satisfies/backed_by → {obligation, control, evidence}`, plus `obligation → grounded_in → regulation_chunk` and `control → grounded_in → policy_chunk`.
- `build_compliance_map(session_id)` does 4 parallel scans, marks obligations as `green` (mapped) or `red` (gap), edges obligation→control labelled with `mapping.gap_status`.

### 5.4 Crawler (`eu-regulatory-corpus/download.py`, ~2.6k lines)
Per-authority scraper functions writing into `docs/<source-tree>/`. Master index `index.csv` (id, source, title, url, sha256, status). Per-host throttling 1.5 s.

Sources:
- **EUR-Lex** — SPARQL (`publications.europa.eu/webapi/rdf/sparql`) by CELEX → manifestation; prefer consolidated. Output `docs/primary/celex_{id}_{slug}.pdf`.
- **EBA** — listing pages (`document_type=250|252|255` → Guidelines / Opinions / Recommendations), BeautifulSoup → `docs/guidance/eba/{slug}.pdf`.
- **ECB** — foedb JSON chunk API + static HTML scrape (Supervisory Guides, Letters, Priorities). Prefer `.en.pdf` then `.en.html`. Output `docs/guidance/ecb/`.
- **ESMA** — downloads `guidelines_tracker.xlsx` (openpyxl), plus Q&A bundle scrape. Strips `_DE/_FR` language suffixes. Output `docs/guidance/esma/`.
- **FATF** — Cloudflare-protected. Uses **Playwright** (chromium headless): land on search page to acquire clearance cookies → `page.evaluate()` faceted-search API → for each result, fetch HTML, find first non-`/translations/` PDF, base64-download. Output `docs/guidance/fatf/`.
- **Irish Statute Book** — probe revised acts → original PDF → HTML print, in that order. Output `docs/national/ireland/primary/`.
- **Central Bank of Ireland** — three modes: index pages, direct PDFs, hub + 1-level subpage traversal. No Playwright needed. Output `docs/national/ireland/guidance/cbi/`.

Idempotency via SHA-256 + `index.csv` upsert (atomic tmp+rename). Status `ok | unchanged | failed`.

### 5.5 Sidecar deployment
ECS Express service `launchlens-sidecar-v4`. CPU 512 / RAM 1024. Image built via `docker buildx build --platform linux/amd64 --push python-backend/` triggered by Terraform `null_resource.sidecar_image_build` whenever any `*.py`, `Dockerfile`, or `pyproject.toml` hash changes. ECR auth via `aws ecr get-login-password`.

### 5.6 Aux scripts (`backend/python-backend/scripts/`)
- `normalize_sanctions.py` — OFAC SDN ingest (sdn / add / alt CSVs from treasury.gov), normalizes, optionally uploads JSONL to `s3://launchlens-sanctions/{source}.jsonl` and seeds `launchlens-sanctions-entities`.
- `parse_nist_controls.py` — extracts NIST-800-53 control families (AC, IA, SC, AU, SI) from PDF or XLSX; infers preventive/detective/corrective; maps to GDPR/ISO27001/PCI-DSS where keywords match. Falls back to a curated fixture if it parses < 30 rows.
- Backend-side seeding: `scripts/seed-regulations.sh`, `seed-corpus.sh` (presign → PUT → finalize), `seed-demo.sh` (curls 3 demo launches), `backfill-display-names.sh` / `.py` (DDB update by filename match).

---

## 6. Frontend — React + TypeScript

### 6.1 Tech stack
React 19.2.5, react-dom 19.2.5, Vite 8.0.10, TypeScript ~6.0.2 (strict, ES2023), Tailwind CSS 3.4.19 (custom Bunq tokens — `--bunq-orange`, `--bunq-orange-hi`, etc.), PostCSS+Autoprefixer. **No** shadcn/ui dep — all components hand-built.

3D / viz: `globe.gl` 2.35, `three` 0.184, `@react-three/fiber` 9.6, `@react-three/drei` 10.7, `d3` 7.9.

Routing: `react-router-dom` 7.14 with one `AppShell` wrapper. State: hooks + Context (`ChatNavContext`); no Redux/Zustand.

Document rendering: `react-pdf` 10.4 (PDF viewer), `react-markdown` 10.1 + `remark-gfm` 4.0 (chat bubbles).

Network: native `fetch` + `EventSource` — no axios. Bearer token in `Authorization` header.

### 6.2 Routes (`App.tsx`)
| Route | Component | Purpose |
|---|---|---|
| `/login` | `LoginPage` | Bearer token + `POST /auth/check`. |
| `/` | `ModeAwareRedirect` | → `/launches` (expansion) or `/jurisdictions` (regulator). |
| `/ask` | `AskPage` | RAG chat with streaming SSE. |
| `/launches` | `LaunchesPage` | List + create. |
| `/launches/new` | `LaunchNewPage` | 3-step wizard. |
| `/launches/:id` | `LaunchDetailPage` | Stream + verdicts + proof packs. |
| `/graph` | `GraphPage` | KB-wide D3 force graph. |
| `/jurisdictions` | `JurisdictionsPage` | Globe + triage. |
| `/jurisdictions/:code` | `JurisdictionsPage` (routed) | |
| `/jurisdictions/:code/launches/:id` | `GraphPage` | Compliance map for one launch × country. |
| `/doc/:docId` | `DocPage` | KB regulation PDF viewer. |
| `/library/:docId` | `LibraryDocPage` | Session-uploaded doc PDF viewer. |
| `/session/:id` | `SessionDetailPage` | Session metadata. |
| `/obligation/:id` | `ObligationDetailPage` | |
| `/control/:id` | `ControlDetailPage` | |
| `/data` | `DataPage` | (stub) |

`AppShell` = grid with `TopNav` header + `ChatRail` sidebar + `<Outlet/>`. Rail is collapsible / overlay on mobile.

### 6.3 API client layer (`src/api/`)
- `client.ts` — `API_BASE` from `import.meta.env.VITE_API_BASE` (default `http://localhost:8080/api/v1`). Helpers `getJson<T>` / `postJson<T>`. 401 clears `launchlens.auth:token` localStorage, dispatches `launchlens:auth-change`, redirects to `/login`.
- `chat.ts` — `postChatStream(req, handlers, signal)`. Parses SSE event blocks split by `\n\n`. Handlers: `onStarted, onDelta, onCitations, onGraphRefs, onCompleted, onFailed`. Also `getChatHistory`, `listChats`, `postRagQuery`.
- `portal.ts` — KB browse: `listDocuments`, `getDocument`. `getGraph()` for the D3 KB graph. `getPresignedUrl(s3Uri)`.
- `launch.ts` — `createLaunch, listLaunches, getLaunch, deleteLaunch, runJurisdiction, rerunFailedJurisdictions, downloadProofPack`. Verdict helpers `jurisdictionLabel`, `jurisdictionFlag`. `jurisdictionSseUrl(launchId, code)` for EventSource.
- `session.ts` — full pipeline + library + evidence surface (`createSession, startPipeline, listObligations, getObligation, listControls, getControl, listMappings, listGaps, listSanctions, presignDocument, finalizeDocument, attachDocument, detachDocument, listLibraryDocuments, getLibraryDocument, presignEvidence, finalizeEvidence, getEvidence, getProofTree, getComplianceMap, getReportUrl, fetchReport, computeSha256Base64, putToPresignedUrl`).
- `jurisdictions.ts` — `listJurisdictionsOverview, getJurisdictionTriage, getComplianceMap`.
- `search.ts` — global search.

### 6.4 Hooks
- `useAuth` — `{token, isAuthenticated, login, logout}` synced with localStorage + custom event.
- `useMode` — `expansion | regulator` (localStorage `launchlens.mode`, custom event `launchlens:mode-change`).
- `useChatList` — global refresh listeners; up to 200 chats; client-side filter.
- `useChatNav` — `{activeChatId, resetToken, selectChat, newChat}` Context provider.
- `useJurisdictionStream` — manages an `EventSource`; auto-reconnect once after 5 s; returns `{events, currentStage, status, lastEvent}`.
- `useJudgesGate` — modal gate around create/delete actions.

### 6.5 3D globe
`WorldMapGlobe.tsx`. Backed by globe.gl over GeoJSON from Natural Earth (with CDN fallback). `backgroundColor:#080808`, `atmosphereColor:#FF7819` (Bunq orange), altitude 0.14. Polygon altitude 0.008 → 0.025 on hover. Releases WebGL context on unmount via `pauseAnimation` + `forceContextLoss` to avoid leaks. ISO-2 → ISO-3 mapping in code.

`WorldMapD3.tsx` — 2D fallback for low-power machines.

### 6.6 Chat (`AskPage.tsx`)
Hero state: category row (`PRIVACY, AML, LICENSING, TERMS, SANCTIONS, REPORTS`) + suggested questions. Submit → user message + pending assistant placeholder → `postChatStream({query, chatId, sessionId}, handlers)`.

Streaming wire format example:
```
event: chat_started
data: {"chatId":"...","timestamp":"..."}

event: chat_citations
data: {"citations":[{"kbType":"REGULATIONS","chunkId":"...","score":0.84,"s3Uri":"s3://launchlens-kb-regulations/...","sourceText":"..."}]}

event: chat_delta
data: {"delta":"Under MiCA Title II, "}
...

event: graph_refs
data: {"refs":[{"launchId":"...","launchName":"...","jurisdictionCode":"NL","jurisdictionName":"Netherlands"}]}

event: chat_completed
data: {"messageId":"...","tokenUsage":{...}}
```

Sources block renders before deltas because `chat_citations` arrives first. `GraphRefChips` chips at the bottom navigate to `/jurisdictions/:code/launches/:id`.

### 6.7 PDF viewer
`react-pdf` 10.4 with workerSrc set to bundled `pdfjs-dist/build/pdf.worker.min.mjs`. Used in `DocPage` (KB PDFs from `/kb/regulations/:id`), `LibraryDocPage` (session-uploaded docs from `/documents/:id`), and to render the proof-pack report PDF.

### 6.8 Env vars
- `VITE_API_BASE` — default `http://localhost:8080/api/v1`.
- `VITE_USE_MOCK` — enables mock data + synthetic graph_refs for offline dev.

---

## 7. Infrastructure — Terraform

Path: `backend/infra/`. Region `eu-central-1`. Provider `~> 6.23` (needed for `aws_ecs_express_gateway_service`). State: local v1 (S3+DynamoDB-lock migration is the post-hackathon plan). All resources tagged `Project=launchlens, ManagedBy=terraform` via provider `default_tags`.

### 7.1 Files
`providers.tf, versions.tf, backend.tf, variables.tf, locals.tf, outputs.tf, terraform.tfvars.example, vpc.tf, iam.tf, secrets.tf, ecr.tf, cloudwatch.tf, cloudfront.tf, ecs_express.tf, ecs_sidecar.tf, jib_build.tf, dynamodb.tf, s3.tf, s3_vectors.tf, bedrock_kb.tf, seed_docs.tf`.

### 7.2 Variables (defaults)
`project_prefix=launchlens`, `region=eu-central-1`, `bedrock_region=eu-central-1`, `aws_profile=default` (real value `mkrestin`), `image_tag=latest`, `amplify_origin=https://*.amplifyapp.com`, `sidecar_base_url=""` (filled post-deploy), `enable_cloudfront_fallback=false`, `opensanctions_api_key=""` (sensitive).

### 7.3 VPC
Default VPC reused (`data "aws_vpc" "default"`). Public subnets selected by `map_public_ip_on_launch=true`. ECS Express tasks deployed there with public IPs. Security groups auto-managed by Express Mode (TODO: lock down sidecar SG).

### 7.4 IAM
**`ecsTaskExecutionRole`** + **`ecsInfrastructureRoleForExpressServices`** — pre-existing AWS-managed roles (read via `data` blocks).

**`launchlens-task-role`** — assumed by backend Fargate tasks. Inline policy:
- DynamoDB: `Get/Put/Update/Delete/Query/Scan/BatchWriteItem/BatchGetItem/DescribeTable` on `arn:aws:dynamodb:${region}:${account}:table/${prefix}-*` and indexes.
- S3: `Get/Put/Delete/ListBucket` on uploads + 3 KB source buckets.
- Bedrock model invoke + invoke-with-stream on `eu.anthropic.*` profiles + `anthropic.*` + `amazon.titan-embed-*` foundation models.
- Bedrock KB: `Retrieve, RetrieveAndGenerate` on all KBs.
- Textract / Polly / Transcribe: blanket `*`.
- Secrets Manager: `GetSecretValue` on `${prefix}/*`.
- CloudWatch Logs: `CreateLogStream/PutLogEvents` on `/ecs/launchlens-*`.

**`launchlens-bedrock-kb-role`** — assumed by Bedrock for KB ingestion. `bedrock:*`, `s3:GetObject + ListBucket` on KB source buckets, `s3vectors:*`.

**`launchlens-sidecar-task-role`** — read-all-DDB; **write only** to `sanctions-hits, audit-log, evidence`; S3 read on `launchlens-sanctions/*` and `launchlens-uploads/*`; Secrets read on both `sidecar-token` and `opensanctions-api-key`; CW Logs on `/ecs/launchlens-sidecar*`.

`ecsTaskExecutionRole` gets an inline policy in `ecs_express.tf` granting `secretsmanager:GetSecretValue` for the two secrets so it can inject them at task startup.

### 7.5 ECS — backend (`launchlens-backend-v5`)
- Cluster `default`. Express Mode auto-provisions ALB + 443 listener + AWS-managed TLS on `*.ecs.eu-central-1.on.aws`.
- CPU 1024 / RAM 2048 (1 vCPU / 2 GB).
- Image: `aws_ecr_repository.backend.repository_url:${var.image_tag}`. Health: `/api/v1/actuator/health` (30 s).
- Port 8080. Logs: `/ecs/launchlens-backend` (7-day retention).

Env injected:
```
AWS_REGION, AWS_BEDROCK_REGION
AWS_BEDROCK_MODEL_IDS_OPUS=eu.anthropic.claude-opus-4-7
AWS_BEDROCK_MODEL_IDS_SONNET=eu.anthropic.claude-sonnet-4-6
AWS_BEDROCK_MODEL_IDS_HAIKU=eu.anthropic.claude-haiku-4-5-20251001-v1:0
AWS_DYNAMODB_*_TABLE  (one per table)
AWS_S3_UPLOADS_BUCKET
KB_REGULATIONS_ID=YIBSPZAPVL
KB_POLICIES_ID=PJSPTYAB1N
KB_CONTROLS_ID=MVGWGRJRJW
SIDECAR_BASE_URL  (resolved from Express endpoint)
SERVER_PORT=8080
ADMIN_TOKEN=demo-test-7f3a9b2c   # demo only
OPENSANCTIONS_API_KEY  (Secrets Manager)
SIDECAR_TOKEN          (Secrets Manager)
```

`network_configuration` uses public subnets, empty SG list. Depends on `null_resource.jib_build` and the secrets policy.

### 7.6 ECS — sidecar (`launchlens-sidecar-v4`)
CPU 512 / RAM 1024. Health `/health`. Port 8001. Image built via `docker buildx --platform linux/amd64 --push`. Logs `/ecs/launchlens-sidecar` (7 d). Env: AWS_REGION + 9× `DYNAMODB_*_TABLE`. Secrets: `SIDECAR_TOKEN, OPENSANCTIONS_API_KEY`.

### 7.7 Jib build (`jib_build.tf`)
`null_resource.jib_build` triggers on SHA1 of `src/**` and `pom.xml`. PowerShell provisioner runs `./mvnw.cmd -B -DskipTests compile jib:build -Djib.to.image=<ecr>:<tag> -Djib.to.auth.username=AWS -Djib.to.auth.password=<ecr-token>`. Base image `eclipse-temurin:25-jre`. Container port 8080. JVM `-XX:InitialRAMPercentage=50.0 -XX:MaxRAMPercentage=75.0` (no fixed `-Xmx`).

### 7.8 DynamoDB declarations
All 14 tables, all `PAY_PER_REQUEST`. The generic `for_each` block creates `mappings, gaps, sanctions-hits, evidence, sanctions-entities, chat-messages, launches`. The rest are declared explicitly so their GSIs can be configured: `sessions` (GSI launch-sessions-index), `obligations` & `controls` (GSI session-id-index, document-id-index), `documents` (GSI kind-last-used-at-index), `audit_log` (GSI session_id-timestamp-index), `jurisdiction_runs` (PK=launch_id, SK=jurisdiction_code, GSI jurisdiction-index), `doc_jurisdictions` (PK=jurisdiction, SK=document_id).

### 7.9 S3 (`s3.tf`)
- `launchlens-uploads-${account_id}` — versioning enabled, SSE-S3 AES-256, all Block Public Access on, CORS for Amplify + localhost (GET/PUT/HEAD/POST, expose ETag, max-age 3000), `force_destroy=true`.
- `launchlens-kb-regulations`, `launchlens-kb-policies`, `launchlens-kb-controls` — read-only CORS (GET/HEAD), expose ETag/Content-Length/Content-Range, SSE-S3, BPA on, no versioning, force_destroy.

### 7.10 S3 Vectors (`s3_vectors.tf`)
Wrapped in `null_resource` because the AWS provider lacks native resources. Creates bucket `launchlens-vectors`. Three indexes (`regulations-idx`, `policies-idx`, `controls-idx`), each: float32, 1024 dim, cosine, with non-filterable metadata keys `AMAZON_BEDROCK_TEXT_CHUNK` + `AMAZON_BEDROCK_METADATA` (so chunk text > 2 KB doesn't blow the filterable limit).

### 7.11 Bedrock KBs (`bedrock_kb.tf`)
Three KBs created via `aws bedrock-agent create-knowledge-base` per source. Each:
- Embedding model `amazon.titan-embed-text-v2:0` (1024-dim, float32).
- Storage `S3_VECTORS` pointing at the corresponding `*-idx`.
- Data source = the matching `launchlens-kb-{source}` bucket.
- Ingestion job auto-started after data source attaches. KB IDs captured to `/tmp/kb_id_<source>.txt`; static fallback IDs in `locals.tf`: `regulations=YIBSPZAPVL, policies=PJSPTYAB1N, controls=MVGWGRJRJW`.

### 7.12 Secrets (`secrets.tf`)
- `launchlens/opensanctions-api-key` — recovery 0 days, default placeholder if `var.opensanctions_api_key` empty.
- `launchlens/sidecar-token` — generated via `random_password` (32 char alphanumeric, no specials → safe in HTTP headers).

### 7.13 CloudWatch
- `/ecs/launchlens-backend` — 7 days
- `/ecs/launchlens-sidecar` — 7 days

### 7.14 CloudFront (optional)
`enable_cloudfront_fallback=true` creates an in-front-of-Express distribution. Cache disabled, `AllViewerExceptHostHeader` origin policy, all HTTP verbs, GET/HEAD cached, redirect-to-https, PriceClass_100. No WAF (AWS managed rules false-positive on PDF multipart bytes as SQLi).

### 7.15 Outputs
`ecr_repository_url, uploads_bucket, kb_source_buckets, dynamodb_tables, task_role_arn, vpc_id, public_subnet_ids, log_group_name, opensanctions_secret_arn, sidecar_token_secret_arn, backend_url, cloudfront_url, vite_api_base (chooses CF if enabled, else Express), kb_ids, sidecar_url, sidecar_ecr_repository_url`.

### 7.16 Seed docs
`seed_docs.tf` walks `seed/{regulations,policies,controls}/**` with `fileset()` and `aws_s3_object` for_each. ETag = `filemd5(file)` so changing content re-uploads. Runs before KB data source ingestion job.

### 7.17 Cost envelope
~$12–20 per 24 h hackathon run. Bedrock token usage dominates.

---

## 8. Backend — Build, Config & Ops

### 8.1 Maven (`pom.xml`)
Spring Boot 4.0.5 parent. Java 25. Key starters: `web, webflux, validation, json, actuator, security, test`. AWS SDK v2 (BOM 2.42.36): `dynamodb, dynamodb-enhanced, s3, s3-transfer-manager, bedrockruntime, bedrockagentruntime, transcribe, polly, textract, secretsmanager, apache-client, netty-nio-client`. Other libs: Lombok, **OpenPDF 2.0.3**, springdoc-openapi 3.0.3, POI 5.3.0 (Excel mapping export). Jib plugin 3.5.1.

### 8.2 `application.yaml` (key entries)
```
spring.application.name: java-backend
spring.config.import: optional:file:.env[.properties]
spring.threads.virtual.enabled: true
server.servlet.context-path: /api/v1

aws.region: eu-central-1
aws.account-id: ${AWS_ACCOUNT_ID:914115115148}

aws.dynamodb.{sessions,obligations,controls,mappings,gaps,sanctions-hits,evidence,sanctions-entities,audit-log,documents,doc-jurisdictions,launches,jurisdiction-runs,chat-messages}-table: launchlens-…
aws.s3.uploads-bucket: ${UPLOADS_BUCKET:launchlens-uploads-914115115148}

aws.bedrock.region: eu-central-1
aws.bedrock.max-concurrent: ${BEDROCK_MAX_CONCURRENT:15}
aws.bedrock.model-ids.{opus,sonnet,haiku}: eu.anthropic.…

kb.regulations-id: ${KB_REGULATIONS_ID:YIBSPZAPVL}
kb.policies-id:    ${KB_POLICIES_ID:PJSPTYAB1N}
kb.controls-id:    ${KB_CONTROLS_ID:MVGWGRJRJW}

cors.allowed-origins: ${CORS_ALLOWED_ORIGINS:https://*.amplifyapp.com,http://localhost:5173}

management.endpoints.web.exposure.include: health,info
management.endpoint.health.show-details: always
management.endpoint.health.probes.enabled: true

app.admin.token: ${ADMIN_TOKEN:}

sidecar.base-url: ${SIDECAR_BASE_URL:http://localhost:8001}
sidecar.token:    ${SIDECAR_TOKEN:dev}

chat.top-k-per-kb: 5
chat.top-n-merged: 10
chat.history-limit: 50

springdoc.api-docs.path: /openapi
springdoc.swagger-ui.path: /swagger-ui.html
springdoc.swagger-ui.operations-sorter: method
springdoc.swagger-ui.tags-sorter: alpha
```

No profile-specific files — environment overrides via `${VAR:default}`. Mock-API mode is documented in `MOCK_API.md` as a future toggle (`MOCK_API_MODE=true`); not currently active.

### 8.3 Code conventions enforced
- No `@Autowired`. Constructor injection via `@RequiredArgsConstructor`.
- No checked exceptions. `RuntimeException`-only.
- Comments only when WHY is non-obvious.
- DTOs `@Data @Builder @NoArgsConstructor @AllArgsConstructor`.
- DDB beans `@DynamoDbBean @NoArgsConstructor @Setter @Builder @AllArgsConstructor` + `@Getter(onMethod_=…)` for annotation placement.
- Presigns always via `S3PresignHelper`.
- Tool JSONs loaded **once at startup** and reused byte-identically.
- SSE always via `SseEmitterService.send(sessionId, eventName, data)`.
- Jackson 3 only.

### 8.4 Tests
Two classes only:
- `JavaBackendApplicationTests` — `@SpringBootTest` `contextLoads()` smoke.
- `JurisdictionInferenceTest` — 7 cases on `JurisdictionInference.inferFromText(String)` (NL/DE/UK/US/DNB/empty/null detection).

Coverage is minimal by design — hackathon trade-off.

### 8.5 Helper scripts (`backend/scripts/`)
- `seed-demo.sh` — POSTs 3 demo launches.
- `seed-regulations.sh` — `presign → PUT (with checksum headers) → finalize` for every PDF in a YAML.
- `seed-corpus.sh` — variants for policies/controls.
- `backfill-display-names.sh` / `.py` — DDB updates for human-friendly `display_name` from `seed/document-titles.yaml`.

---

## 9. Real DynamoDB Table Samples (live data, eu-central-1)

Item counts and 1–2 representative items, fetched from `aws dynamodb scan --max-items {1|2}` on 2026-04-26.

### 9.1 Counts (across all 14 tables)
```
obligations         20 807
mappings            14 957
audit-log           19 769
gaps                 2 240
controls             1 667
doc-jurisdictions      150
chat-messages          122
sessions                70
jurisdiction-runs       70
documents               52
launches                10
evidence                 0
sanctions-hits           0
sanctions-entities       0
```
(eventual-count; may lag scans by minutes.)

### 9.2 launchlens-obligations (sample)
```json
{
  "id":            {"S": "obl-e640dfcb-fae3-47bd-acbb-8457a13abc0a"},
  "session_id":    {"S": "c4e79600-5551-4351-92de-a798f6397176"},
  "document_id":   {"S": "b1e0d8604ef802d8c5e2652b441fcda206b508f6d39de742987da5b143846b3b"},
  "deontic":       {"S": "O"},
  "subject":       {"S": "Member States"},
  "action":        {"S": "Notify the Commission without delay of any amendment affecting the rules on penalties and measures"},
  "conditions":    {"L":[{"S":"Change concerns rules and measures previously notified under Article 13 of Regulation (EC) No 924/2009"}]},
  "risk_category": {"S": "Regulatory Governance / Notification"},
  "extraction_confidence": {"N": "0.98"},
  "extracted_at":  {"S": "2026-04-25T07:22:51.933028Z"},
  "source": {"M": {
    "regulation": {"NULL": true},
    "article":    {"NULL": true},
    "section":    {"NULL": true},
    "paragraph":  {"NULL": true},
    "retrieved_from_kb_chunk_id": {"NULL": true},
    "source_text": {"S":"Member States shall, without delay, notify the Commission of any amendment affecting the rules and measures of which it was notified in accordance with Article 13 of Regulation (EC) No 924/2009."}
  }}
}
```
Second example (consumer protection / complaints):
```json
{
  "id":            {"S": "obl-40b59ca9-ce5e-450b-9709-d31c3cb045f8"},
  "session_id":    {"S": "728888ab-2fef-47ed-a836-061d4c94c7b5"},
  "document_id":   {"S": "b1e0d8604ef802d8c5e2652b441fcda206b508f6d39de742987da5b143846b3b"},
  "deontic":       {"S": "O"},
  "subject":       {"S": "Member States"},
  "action":        {"S": "Provide for procedures allowing payment service users and other interested parties to submit complaints to the competent authorities regarding alleged infringements of this Regulation by payment service providers."},
  "conditions":    {"L":[]},
  "risk_category": {"S": "Consumer protection / complaint procedures"},
  "extraction_confidence": {"N": "0.99"},
  "source": {"M":{ "source_text":{"S":"Member States shall provide for procedures which allow payment service users and other interested parties to submit complaints to the competent authorities with regard to alleged infringements of this Regulation by payment service providers."} }}
}
```

### 9.3 launchlens-controls (sample)
```json
{
  "id":          {"S": "ctrl-61605fbe-0eca-49b5-8e16-aaec94eb21ec"},
  "session_id":  {"S": "eb2d7497-d184-4785-b622-502d48c3bbba"},
  "document_id": {"S": "96023c98adca0bad9b59bd979b88d0eae11e8baf5d502cb1bc97db50de5528c8"},
  "description": {"S": "Standard Contractual Clauses (SCCs) for international data transfers; all third parties outside EU subject to SCCs for data protection"}
}
```
(In production, many controls have only `id, session_id, document_id, description` set — extra fields like `control_type, category, owner` populate where the LLM extracted them.)

### 9.4 launchlens-mappings — both routes

**Cached / partial / unverified mapping** (from random row):
```json
{
  "id":                   {"S": "MAP-2c6f40b8e23e9ef4"},
  "obligation_id":        {"S": "obl-d9dbe589-e93e-4c78-aab9-107f1571221c"},
  "control_id":           {"S": "ctrl-78b3e2b1-4ae1-4794-b618-8cadd6a552a2"},
  "session_id":           {"S": "73a0db28-bca3-41bd-84e4-49d7458e0395"},
  "mapping_type":         {"S": "partial"},
  "mapping_confidence":   {"N": "0"},
  "gap_status":           {"S": "partial"},
  "metadata":             {"M": {"route": {"S": "llm"}}},
  "semantic_reason":      {"S": "Supervisory Board review of risk assessments addresses governance oversight, not capital requirements."},
  "reviewer_notes":       {"S": "ground-check failed: claim not found in source text"}
}
```

**High-confidence direct mapping (verified)**:
```json
{
  "id":                  {"S": "MAP-f0fcd682d023db53"},
  "obligation_id":       {"S": "obl-a47af7f5-f0a4-4c80-bf69-f5e6917d2915"},
  "control_id":          {"S": "ctrl-3578db0c-a8a2-4a41-8409-c7ac3fdd1d12"},
  "session_id":          {"S": "08474979-ab00-4308-a788-aa7bd7fc36b0"},
  "mapping_type":        {"S": "direct"},
  "mapping_confidence":  {"N": "70"},
  "gap_status":          {"S": "satisfied"},
  "metadata":            {"M": {"route": {"S": "llm"}}},
  "semantic_reason":     {"S": "Mandatory internal reporting by all employees within 24 hours of suspicion creates the foundational mechanism to capture material ML/TF information before it can be omitted or withheld from the MLRO and ultimately the Bank."}
}
```
Note: `MAP-…` IDs are 16-hex-char prefixes (= first 16 chars of `sha256(obligationId + "#" + controlId)`).

### 9.5 launchlens-gaps (sample)
```json
{
  "id":                {"S": "gap-a9302cff-eb8f-4926-bd45-fe5bc42f64fa"},
  "session_id":        {"S": "90509282-3d61-499a-b5e9-a52cc7ecfbca"},
  "obligation_id":     {"S": "obl-3ee2f823-633d-4185-aebb-69af4504e522"},
  "gap_type":          {"S": "control_missing"},
  "gap_status":        {"S": "gap"},
  "escalation_required":{"BOOL": false},
  "residual_risk":     {"N": "0"},
  "recommended_actions":{"L": []}
}
```

### 9.6 launchlens-sessions (sample, list trimmed)
```json
{
  "id":                {"S": "e1648975-7da4-49d2-b201-3433da3c5e14"},
  "state":             {"S": "EXTRACTING"},
  "createdAt":         {"S": "2026-04-25T10:20:34.627856Z"},
  "updatedAt":         {"S": "2026-04-25T10:20:40.367175Z"},
  "launch_id":         {"S": "bca38dbb-d880-4630-9eb0-7d77e63472d5"},
  "jurisdiction_code": {"S": "IE"},
  "completed_stages":  {"L": [{"S": "INGEST"}]},
  "document_ids":      {"L": [
      {"S":"4cb921f6305d…"}, {"S":"a51acc10f621…"}, {"S":"209379e3991b…"},
      {"S":"cec2e7393dfe…"}, {"S":"b6b2f4d1d2ac…"}, {"S":"bc8632e21a33…"},
      /* … 50 documents total for the IE Mini Demo session … */
  ]}
}
```
Real sessions in production attach **~50 documents** (this `IE Mini Demo` run uses 50 entries: 17 EU/IE primary regulations + 25+ bunq policies + a brief).

### 9.7 launchlens-documents (sample)
```json
{
  "id":             {"S": "df546f2364aaf9e2fc5e718ff47e6ccca6b553a83d21969483cb28f6e9cfbb6a"},
  "filename":       {"S": "celex_32024L1640_amld6.pdf"},
  "display_name":   {"S": "AMLD6 — Sixth Anti-Money Laundering Directive (EU 2024/1640)"},
  "kind":           {"S": "regulation"},
  "content_type":   {"S": "application/pdf"},
  "size_bytes":     {"N": "2172837"},
  "page_count":     {"N": "124"},
  "jurisdictions":  {"SS": ["EU"]},
  "first_seen_at":  {"S": "2026-04-25T01:34:39.123920729Z"},
  "last_used_at":   {"S": "2026-04-25T13:57:13.816089097Z"},
  "extracted_at":   {"S": "2026-04-25T02:13:01.737598300Z"},
  "extraction_s3_key":{"S":"extractions/df546f2364aaf9e2fc5e718ff47e6ccca6b553a83d21969483cb28f6e9cfbb6a.txt"},
  "s3_key":         {"S": "documents/df546f2364aaf9e2fc5e718ff47e6ccca6b553a83d21969483cb28f6e9cfbb6a.pdf"},
  "obligations_extracted":{"BOOL": false},
  "controls_extracted":   {"BOOL": false}
}
```
Document IDs are SHA-256 of the file content. `extracted_text` field is omitted in scan output but exists for cached documents.

### 9.8 launchlens-launches (sample)
```json
{
  "id":            {"S": "ad11b984-d0ec-419f-aa37-c7898ad04869"},
  "name":          {"S": "IT - Open finance expansion"},
  "kind":          {"S": "PRODUCT"},
  "license":       {"S": "EMI"},
  "status":        {"S": "CREATED"},
  "brief":         {"S": "Roll out an account-aggregation feature for Italian retail customers via PSD2 AISP, with consent flows aligned to Banca d'Italia guidance."},
  "counterparties":{"L":[{"S":"Banca d'Italia"},{"S":"TrueLayer"},{"S":"Tink"}]},
  "created_at":    {"S": "2026-04-25T09:49:08.433216Z"},
  "updated_at":    {"S": "2026-04-25T09:49:08.433216Z"}
}
```
```json
{
  "id":            {"S": "bca38dbb-d880-4630-9eb0-7d77e63472d5"},
  "name":          {"S": "IE Green Demo (synthetic, fully aligned)"},
  "kind":          {"S": "PRODUCT"},
  "license":       {"S": "EMI"},
  "brief":         {"S": "Launch a small Irish e-money product with full KYC, sanctions, AML, incident response, and records retention controls in place."},
  "counterparties":{"L":[{"S":"Ireland"}]},
  "status":        {"S": "CREATED"}
}
```

### 9.9 launchlens-jurisdiction-runs (sample)
```json
{
  "launch_id":          {"S": "ad11b984-d0ec-419f-aa37-c7898ad04869"},
  "jurisdiction_code":  {"S": "DE"},
  "current_session_id": {"S": "7705dd87-520b-460e-bb6a-71e04ce6fc69"},
  "status":             {"S": "PENDING"},
  "gaps_count":         {"N": "0"},
  "sanctions_hits":     {"N": "0"},
  "last_run_at":        {"S": "2026-04-26T18:46:24.856312501Z"}
}
```

### 9.10 launchlens-doc-jurisdictions (sample)
```json
{
  "jurisdiction":  {"S": "US"},
  "document_id":   {"S": "1a51fc8a8b6d6b6a24485d80e645ce8bccfa9770907c6a06056755dbe504b7ec"},
  "filename":      {"S": "bunq-policy-cookies-nl.pdf"},
  "kind":          {"S": "policy"},
  "last_used_at":  {"S": "2026-04-25T01:35:04.641173592Z"}
}
```

### 9.11 launchlens-audit-log (sample)
```json
{
  "id":           {"S": "62d418f7-649c-4930-81b9-d914c0b40e50"},
  "session_id":   {"S": "23a3425d-4475-464b-8d77-34ddcc4ab64f"},
  "mapping_id":   {"S": "MAP-c0dd5c3340bbc6a9"},
  "action":       {"S": "mapping_created"},
  "actor":        {"S": "pipeline:map-obligations-controls"},
  "timestamp":    {"S": "2026-04-25T08:48:54.022084500Z"},
  "prev_hash":    {"S": "2c2ef70cce4f80c2a09d2c63d0632bd5c2ffc4ae43e119adf0828d5c3d01761c"},
  "entry_hash":   {"S": "71c05a92b9d829f93bfaf9ac651c12279cca23a5faf3316b6cf0402dc8b30f17"},
  "payload_json": {"S": "{\"obligation_id\":\"obl-528bdbdf-…\",\"control_id\":\"ctrl-242c96d0-…\",\"evidence_sha256s\":[],\"confidence\":0.0}"}
}
```

### 9.12 launchlens-chat-messages (sample)
```json
{
  "id":        {"S": "5c6e5c06-4eb8-4a83-81e9-4821049d192b"},
  "chatId":    {"S": "f6d92f16-b9d5-4367-ad0e-ceb6d7fd1f39"},
  "role":      {"S": "USER"},
  "content":   {"S": "Can we offer crypto custody under MiCA?"},
  "timestamp": {"S": "2026-04-25T03:53:45.584332Z"}
}
```

### 9.13 EMPTY tables (live)
- `launchlens-evidence` — 0 items. Evidence flow exists but no rows yet in this account.
- `launchlens-sanctions-hits` — 0 items. Demos run with zero counterparties.
- `launchlens-sanctions-entities` — 0 items. OFAC/EU/UN/UK normalization scripts haven't been run against this account.

---

## 10. Proof Pack — Real Artifact Contents

Path: `D:\Programs\Java\Java Project\Bunq\files\` contains an unpacked proof pack from the deployed system:

```
audit_trail.json   1 980 735 bytes  hash-chained audit log JSON array
cover.pdf              2 633 bytes  verdict cover page
gaps.pdf             191 980 bytes  full gap list + narrative
sanctions.pdf          1 090 bytes  sanctions section (empty for this run)
mappings.xlsx         14 584 bytes  obligation × control matrix (Apache POI export)
```

### 10.1 cover.pdf (verbatim text)
```
IE_MINI — IE Mini Demo v5 (KB-search MAP + batched GC) Compliance Evidence Pack
Generated: 2026-04-26T18:47:37.387661989Z | Run #1 | Verdict: RED

Launch: IE Mini Demo v5 (KB-search MAP + batched GC)
Launch a small Irish e-money product, customers onboarded via Onfido KYC.
Jurisdiction: IE_MINI — IE_MINI
Run timestamp: 2026-04-25T09:58:18.276487200Z
Pipeline version: v1

Counts:
129 obligations / 65 controls / 2081 mappings / 100 gaps / 0 sanctions hits

Policy versions used:
• ie_si_2011_183_emd2_transposition.pdf SHA-256:bc8632e21a33 last used:2026-04-25T13:57:13.816089097Z
• bunq-Control-KYC-Onboarding.pdf       SHA-256:cec2e7393dfe last used:2026-04-25T13:57:13.816089097Z
• bunq-Control-AML-CFT-Framework.pdf    SHA-256:b6b2f4d1d2ac last used:2026-04-25T13:57:13.816089097Z

Unresolved gaps:  (one bullet per gap, format: "<regulation_slug> <article|section> score=<0.00>")
• ie_si_2011_183_emd2_transposition 28 score=0.81
• ie_si_2011_183_emd2_transposition 21 score=0.81
• ie_si_2011_183_emd2_transposition 14 score=0.86
…
• ie_si_2011_183_emd2_transposition 21 score=0.81  (~120 lines)

Owner / Contact: compliance@bunq.com
```

### 10.2 sanctions.pdf (verbatim)
```
Sanctions Screening — IE_MINI run
Run timestamp: 2026-04-25T09:58:18.276487200Z
Lists screened: OFAC SDN, EU Consolidated, UN, UK OFSI

No counterparties screened for this jurisdiction.
```
(IE Mini Demo run had no counterparties.)

### 10.3 gaps.pdf
~6 pages, full list of 100 gaps with same line format. Larger because it includes the per-gap recommended action and narrative.

#### 10.3.1 Per-gap page shape (the section repeated 100× in this run)
Each gap renders as one block of fixed-shape text:
```
Gap — <regulation_slug> <article_or_section>

Obligation: <full obligation statement, may be truncated with …>

Severity
Regulatory urgency: 0.NN
Penalty severity:   0.NN
Probability:        0.NN
Business impact:    0.NN
Combined score:     0.NN          ← 4-dim weighted average

Gap type: <control_missing | control_weak | control_untested | control_expired>
Narrative: <multi-paragraph LLM output explaining the regulatory / operational / reputational /
            systemic risks; references blast radius / detectability / recoverability inline>

Remediation
• <recommended_action[0]>
• <recommended_action[1]>
• …                              (~5–8 bullets, varies by severity)

Owner: <suggested_owner>
Target date: <YYYY-MM-DD>
Rerun history: Run #1 — first detected — current
```

#### 10.3.2 Three representative pages (verbatim from this proof pack)

**Example A — Gap 28 (fund exchange, AMBER, combined 0.81)** — the obligation example the team uses in demos:
```
Gap — ie_si_2011_183_emd2_transposition 28

Obligation: The receipt of funds by an electronic money institution from an electronic money holder
shall be exchanged for electronic money without delay

Severity
Regulatory urgency: 0.90
Penalty severity:   0.85
Probability:        0.70
Business impact:    0.80
Combined score:     0.81

Gap type: control_missing
Narrative: This obligation requires electronic money institutions to exchange funds received from
electronic money holders for electronic money without delay. The absence of satisfactory control
coverage creates a critical compliance gap. Failures to process fund exchanges promptly expose the
institution to:
1. Regulatory Risk: EMI regulations (PSD2, national EMI frameworks) mandate rapid fund processing.
   Delays violate core operational requirements and trigger regulatory enforcement.
2. Operational Risk: Without controls ensuring timely processing, funds may remain unexchanged,
   creating customer disputes, loss of trust, and potential system bottlenecks.
3. Reputational Risk: Customer complaints about delayed fund exchanges damage market reputation
   and customer retention.
4. Systemic Risk: If widespread, delayed exchanges affect multiple customers and reduce overall
   operational reliability.
The high blast radius (0.9) reflects that processing delays impact all fund-holding customers
simultaneously. Detectability is moderate-to-high (0.75) because delays may not be immediately
obvious without transaction monitoring. Recovery effort is significant (0.6) as affected customers
may require compensation and manual intervention.

Remediation
• Implement automated fund exchange processing system with real-time or same-day settlement capability
• Establish and document SLAs for fund exchange completion (target: same-day or per regulatory
  requirement) with monitoring dashboards
• Deploy transaction-level monitoring to detect delays and flag exceptions in real-time
• Conduct root-cause analysis of any historical delays and implement corrective actions
• Develop and test incident response procedures for processing failures or bottlenecks
• Train staff on fund exchange obligations and manual escalation procedures
• Review third-party settlement partners to ensure their SLAs align with the regulatory requirement

Owner: compliance-officer
Target date: 2026-07-25
Rerun history: Run #1 — first detected — current
```

**Example B — Gap 28 (deposit-taking prohibition, RED, combined 0.91)** — the highest-severity gap in this run; LLM tagged it "CRITICAL / existential":
```
Gap — ie_si_2011_183_emd2_transposition 28

Obligation: An electronic money institution shall not engage in the business of taking deposits or
other repayable funds.

Severity
Regulatory urgency: 0.95
Penalty severity:   0.90
Probability:        0.85
Business impact:    0.95
Combined score:     0.91

Gap type: control_missing
Narrative: Electronic money institutions (EMIs) are subject to strict regulatory limitations on their
permitted activities. This obligation restricts EMIs from engaging in traditional deposit-taking or
accepting other repayable funds, which is a core banking function reserved for licensed credit
institutions. Violation of this prohibition constitutes a material breach of financial services
regulations (e.g., PSD2, EMI licensing directives in EU/EEA frameworks, or equivalent national
regulations).

The risk is CRITICAL because: (1) accepting deposits without proper banking license is illegal and
exposes the organization to immediate enforcement action, (2) the entire organization could be
forced to cease operations, (3) customers' funds could be at severe risk of loss if the EMI lacks
deposit guarantees and proper capital reserves, (4) regulatory penalties can be substantial (often
10–50% of annual turnover or higher), and (5) the violation is difficult to conceal once discovered
through audits or customer complaints.

Current status: No adequate control is in place to prevent EMIs from accepting deposits or
repayable funds, creating an existential compliance and operational risk.

Remediation
• Implement strict product and service governance controls to prohibit deposit-taking and repayable-
  funds acceptance. Document approved product offerings and establish automated system controls
  to reject or prevent any transaction that constitutes a deposit or repayable fund.
• Conduct immediate compliance audit of all current customer accounts, transaction types, and
  product offerings to identify any existing violations or high-risk activities.
• Establish clear business policies and procedures defining what activities ARE permitted for the
  EMI (e-money issuance, payment services, pre-funded wallet operations) versus what is strictly
  forbidden (deposit-taking, credit extension without license).
• Deploy customer-facing and backend messaging clearly communicating that the organization is an
  electronic money institution and does NOT accept deposits. Include explicit disclaimers in customer
  agreements.
• Implement real-time monitoring and alerting for any transactions or account activity that
  resembles deposit-taking (e.g., large inbound transfers labeled as deposits, interest payments,
  or repayment terms).
• Schedule quarterly compliance training for all staff involved in customer onboarding, product
  delivery, and finance.
• Engage with regulatory authority to conduct a voluntary disclosure or gap-closure review if any
  historical violations are identified.

Owner: compliance-officer
Target date: 2026-07-25
Rerun history: Run #1 — first detected — current
```
Note: there are two gaps tagged `28` in this run because the same article number appears in two different sections of the source SI, and the v3 pipeline's article-detection is positional. v4 coverage architecture would deduplicate.

**Example C — Gap 40 (acquirer notification deadline, low, combined 0.64)** — a procedural gap, much shorter narrative + 5-bullet remediation:
```
Gap — ie_si_2011_183_emd2_transposition 40

Obligation: In its acknowledgement of receipt of a notification referred to in paragraph (1), the
Bank shall inform the proposed acquirer concerned of the date on which the assessment period will end.

Severity
Regulatory urgency: 0.75
Penalty severity:   0.60
Probability:        0.55
Business impact:    0.65
Combined score:     0.64

Gap type: control_missing
Narrative: The Bank is obligated to inform a proposed acquirer of the end date of the assessment
period in its acknowledgement of receipt. This procedural transparency obligation ensures that
acquisition parties have clear visibility into regulatory timelines. The absence of control creates
risks around missed deadlines, failed communications, and regulatory non-compliance. The impact
affects the specific acquisition transaction and parties involved, but could cascade if
communication failures occur systematically. Detection of this miss may occur after the fact when
the acquirer raises questions or the regulatory period has already elapsed.

Remediation
• Establish a formal communication protocol requiring automatic inclusion of assessment period end
  date in all acquirer acknowledgement letters
• Create a template-based acknowledgement form with mandatory fields for assessment period dates
  to prevent omission errors
• Implement a checklist review process requiring sign-off that the assessment period end date has
  been clearly communicated before acknowledgement is sent
• Conduct training for all staff involved in acquisition communications on this specific procedural
  transparency requirement
• Audit recent acquisition acknowledgements to identify any past instances of non-compliance and
  notify relevant parties if gaps are found

Owner: compliance-officer
Target date: 2026-07-25
Rerun history: Run #1 — first detected — current
```

#### 10.3.3 Observed in this run (IE_MINI)
- All 100 gaps are `control_missing`. The other gap_types (`control_weak | control_untested | control_expired`) exist in the schema but didn't fire this run.
- Combined-score range: **0.63 → 0.91** (light procedural → existential).
- `Owner` is uniformly `compliance-officer`; `Target date` uniformly `2026-07-25`. These come from `recommended_actions[]` in `score_gap.json` tool output and are LLM-suggested defaults — the team noted they should become per-gap in v4.
- `Rerun history` is one line right now because every gap is fresh. After a re-run of the same launch×jurisdiction the line becomes `Run #1 — first detected · Run #2 — still open · Run #3 — closed`, etc.

### 10.4 mappings.xlsx
~15 KB Excel workbook with one row per mapping (~2,081 rows) with columns plausibly:
- mapping_id (`MAP-…`)
- obligation_id (`obl-…`)
- control_id (`ctrl-…`)
- mapping_type (direct / partial / requires_multiple)
- mapping_confidence (0..100)
- gap_status (satisfied / partial / gap / under_review)
- semantic_reason
- route (llm / cached)

Generated by `ReportService` / a dedicated mappings exporter using Apache POI (declared in `pom.xml`).

### 10.5 audit_trail.json (1.9 MB)
JSON array of audit events for the IE_MINI session `08474979-ab00-4308-a788-aa7bd7fc36b0`. Each entry:
```json
{
  "ts":          "2026-04-25T09:52:02.426488800Z",
  "event":       "mapping_created",
  "entry_hash":  "d8e7de75235d05b0…",
  "prev_hash":   "",                                   // first entry has empty prev_hash
  "actor":       "pipeline:map-obligations-controls",
  "session_id":  "08474979-ab00-4308-a788-aa7bd7fc36b0",
  "mapping_id":  "MAP-285c839466c767c1",
  "payload": {
    "evidence_sha256s": [],
    "control_id":       "ctrl-d9b15ceb-2ecd-451b-8ae8-e331ed49404f",
    "obligation_id":    "obl-1ea42133-645e-4e69-ba61-7349e1175c0b",
    "confidence": 45.0
  }
}
```
Subsequent entries:
- `event` toggles between `mapping_created` (during MapObligationsControlsStage) and `mapping_ground_check_failed` (during GroundCheckStage). `mapping_verified` exists too.
- `prev_hash` of entry N = `entry_hash` of entry N-1 (chain).
- Final entries are the ground-check failures, payload `{reason:"not found in retrieved chunk", evidence_sha256s:[]}`.

This matches `AuditLogService.append()` exactly: alphabetical-field canonical SHA-256 of `action|actor|id|mappingId|payload|prevHash|sessionId|timestamp`.

---

## 11. Useful File Paths (quick reference)

```
backend/                                Spring Boot app
├── pom.xml                             Java 25 / Boot 4.0.5 / AWS SDK 2.42.36 / OpenPDF / Jib 3.5.1 / POI
├── mvnw / mvnw.cmd                     Wrapper used by Terraform Jib
├── application.yaml                    src/main/resources
├── src/main/java/com/bunq/javabackend/
│   ├── JavabackendApplication.java
│   ├── config/                         AwsConfig, DynamoDbConfig, CorsConfig, SecurityConfig, health/
│   ├── controller/                     20 controllers + common/ErrorController
│   ├── web/GlobalExceptionHandler.java @Valid 400 + IllegalState 409
│   ├── client/SidecarClient.java       WebClient → Python FastAPI
│   ├── service/                        BedrockService, BedrockStreamingService, TextractAsyncService, TranscribeAsyncService, EvidenceService, ReportService, AuditLogService, ChatService, ControlService, ObligationService, MappingService, GapService, SanctionsService, sse/SseEmitterService
│   │   └── pipeline/                   PipelineOrchestrator + Context + Stage enum + IngestedDocument
│   │       ├── prompts/SystemPrompts.java
│   │       ├── bedrock/ToolDefinitions.java + tools/*.json
│   │       └── stage/                  IngestStage … NarrateStage
│   ├── repository/                     14 repositories
│   ├── model/                          @DynamoDbBean entities + enums/ + BedrockModel
│   ├── dto/{request,response,response/sidecar,response/events}/
│   ├── helper/                         S3PresignHelper, IdGenerator, mapper/
│   └── exception/                      SessionNotFoundException, MappingNotFoundException, NotFoundException, EntityAlreadyExistsException, SidecarCommunicationException, ForbiddenException
├── src/main/resources/
│   ├── application.yaml
│   └── prompts/tools/                  extract_obligations.json, extract_controls.json, match_obligation_to_controls.json, score_gap.json, ground_check.json, batch_ground_check.json, extract_counterparties_from_brief.json
├── src/test/java/com/bunq/javabackend/ JavaBackendApplicationTests, util/JurisdictionInferenceTest
├── infra/                              Terraform (see §7)
├── python-backend/                     FastAPI sidecar
│   ├── app/{main.py, config.py, deps.py, routers/{health,sanctions,evidence,proof_tree}.py, services/{sanctions_screener.py, dag_builder.py}}
│   ├── Dockerfile, pyproject.toml
│   └── scripts/{normalize_sanctions.py, parse_nist_controls.py}
├── scripts/                            seed-demo.sh, seed-regulations.sh, seed-corpus.sh, backfill-display-names.sh, backfill_display_names.py
├── seed/{regulations,policies,controls}/   PDF seed corpus (S3-uploaded by Terraform)
├── API.md, BACKEND.md, CODE_PATTERNS.md, DEPLOYMENT.md, DOCUMENTS_API.md, DYNAMODB.md, EXCEPTIONS.md, INFRA_GUIDE.md, MAPPERS.md, MOCK_API.md, PROMPT_CACHE.md, SIDECAR.md, STACK.md, STRUCTURE.md
│
eu-regulatory-corpus/                   Crawler
├── download.py                         ~2.6k lines, per-authority scrapers
├── sources.yaml                        CELEX IDs, national configs
├── index.csv                           SHA-256 / status registry
├── docs/                               Local cache (1,217 PDFs ≈ 845 MB)
└── README.md
│
frontend/                               React 19 + Vite + Tailwind
├── package.json                        deps in §6.1
├── vite.config.ts, tsconfig*.json, tailwind.config.js, postcss.config.js
├── public/
└── src/
    ├── App.tsx                         Routes (§6.2)
    ├── main.tsx
    ├── api/                            client.ts, chat.ts, portal.ts, launch.ts, session.ts, jurisdictions.ts, search.ts
    ├── components/                     AppShell, TopNav, ChatRail, VerdictPill, KindBadge, ModeToggle, HeroGradient, PrismCanvas, BackdropMesh, GlassPrisms, WorldMapGlobe, WorldMapD3, GraphRefChips, SearchPalette, HeaderSearch, icons/, RequireAuth, JudgesOnlyModal
    ├── hooks/                          useChatList.ts, useJurisdictionStream.ts, useJudgesGate, …
    ├── auth/                           useAuth.ts
    ├── lib/                            chatNav.ts (Context)
    └── pages/                          AskPage, LaunchesPage, LaunchNewPage, LaunchDetailPage, JurisdictionsPage, GraphPage, DocPage, LibraryDocPage, SessionDetailPage, ObligationDetailPage, ControlDetailPage, LoginPage, DataPage, ModeAwareRedirect
│
files/                                  Real artifacts captured for context
├── Screenshot 2026-04-25 at 13.43.20.png
├── photo_*_2026-04-26_20-32-47.jpg
├── pirsm.txt                           Hackathon summaries v1+v2 + promo video transcript
├── prism.txt                           ← THIS FILE
├── audit_trail.json                    Hash-chained audit log (1.9 MB)
├── cover.pdf                           Proof-pack cover (RED verdict, IE_MINI)
├── gaps.pdf                            Proof-pack gaps section
├── sanctions.pdf                       Proof-pack sanctions section
└── mappings.xlsx                       Proof-pack mappings export
```

---

## 12. Locked decisions / non-obvious gotchas

- **Verdicts are deterministic Java math.** The LLM emits dimensions; the colour is computed. Don't put a "trust the LLM verdict" code path back in.
- **Citations stream first.** `chat_citations` SSE event must reach the client before `chat_delta` so the Sources block paints before the text. Don't reorder.
- **Tool JSON is byte-identical across calls** to keep the Bedrock prompt cache hot. Loading once at startup is intentional. Don't `objectMapper.writeValueAsString(tools)` per call.
- **PDF bytes never transit the JVM.** Textract async on S3 + S3 Additional Checksums for evidence hashing. Don't reintroduce a JVM-side hasher or PDF parser.
- **Mapping IDs are deterministic** (`MAP-` + first 16 hex chars of `sha256(obligationId#controlId)`). This is a cache key — don't change the format.
- **Ground-check is batched (50)** with Nova Pro via Converse API, not Anthropic. Per-mapping calls were the v1 way and were 50× slower.
- **CBI uses httpx + BS4. FATF needs Playwright** — Cloudflare blocks plain HTTP. Don't try to switch FATF to httpx; you'll just get 403s.
- **`*` Bedrock IAM is intentional** for `s3vectors:*` and the foundation-model wildcard ARNs because the AWS provider doesn't model resource-level permissions for either yet.
- **Mock API mode** documented in MOCK_API.md but not yet wired (no `MOCK_API_MODE=true` toggle in code today).
- **CloudFront has no WAF** — AWS managed rules false-positive on PDF multipart bytes. Adding WAF will break document upload.
- **State backend is local file** — fine for hackathon; migrate to S3 + DynamoDB lock before any production work.
- **Default VPC is reused** instead of building a new one. Tasks live in public subnets, no NAT gateway.

---

## 13. Open / unfinished

- Mock API toggle (`MOCK_API_MODE`) — surface defined in `MOCK_API.md`, not yet implemented.
- Test coverage is ~2% (smoke + 7 regex cases). Service / repo / controller tests are TODO.
- Sidecar SG is auto-managed and wide-open — TODO to lock down.
- Sanctions tables are empty in this account — `normalize_sanctions.py` ingest hasn't been run.
- Evidence rows are 0 — flow works but isn't exercised by demos.
- v4 coverage architecture (`launchlens-doc-jurisdictions` denormalised table + `Document.jurisdictions` Set) is partly in place (table exists with 150 items) but `AutoDocService.forJurisdiction` may still fall back to filename inference under some paths.

---

## 14. Pitch Deck & Presentation Script

Captured verbatim from the team (Misha Z., Leonid M.) for use as the external-facing narrative. Tone across all materials: **confident, founder-energy, zero corporate fluff.** Every slide should be readable as a standalone screenshot. Max 3 lines of body text per slide.

### 14.1 Visual language
- Clean, modern fintech.
- **Dark background, single accent colour** (electric blue or neon green).
- Bold sans-serif headlines, generous whitespace.
- Minimal text per slide, **large numbers** where applicable.
- **No stock photos.** Abstract geometric shapes, subtle 3D globe motifs, data-viz elements (nodes, graphs, world-map fragments).

### 14.2 Slide deck (5 slides)

**Slide 1 — The Problem**
- Headline: *"It just… takes… so long."*
- Sub: We flew to Amsterdam — to Bunq Update — and sat down with **Kris Wulteputte** and others from the Risk Operations, Expansion, and compliance teams. Different roles, different desks. Every conversation ended in the same sentence.
- Visual: stylised clock or hourglass dissolving into regulatory document fragments. Small "Amsterdam · Bunq Update" location tag. Three quote cards from the three personas (Kris named once; the other two shown by title only).

**Slide 2 — The Competitive Edge Nobody Talks About**
- Headline: *"Revolut's real moat isn't marketing. It's compliance speed."*
- Sub: Fast jurisdiction expansion is an internal compliance machine. Today, every other fintech pays for that gap in weeks of manual work.
- Visual: world map with Revolut's footprint highlighted vs. a slower competitor; bar chart "weeks to launch" comparison.

**Slide 3 — What We Built**
- Headline: *"A fully functional MVP. On your stack."*
- Sub: End-to-end pipeline running on AWS Bedrock, S3 Vectors, DynamoDB, Fargate. Ingest → extract obligations → map to controls → gap analysis → sanctions screen → ground-check → audit-ready proof pack. Hash-chained audit log. Multi-jurisdiction by default.
- Visual: clean architecture diagram (8-stage horizontal pipeline) with AWS service icons. Small badge: *"Pipeline optimised 20× during the hackathon."*

**Slide 4 — Dream Outcome**
- Headline: *"From quarters to weeks. Sometimes days."*
- Sub bullets:
  - New jurisdiction: launched in weeks, not quarters.
  - Every Monday: CRO opens one dashboard.
  - Bank-wide compliance status — provable, with cryptographic evidence.
  - Not "we think so." **Provable.**
- Visual: split screen — left "Today" calendar with 6 weeks blocked out; right "With Bunq Copilot" calendar with 5 days. Below: stylised proof-pack document with checksum/hash visual.

**Slide 5 — What's Next**
- Headline: *"Let's tailor it to your user stories."*
- Sub: We want to sit with Bunq's compliance and expansion teams again — this time co-designing around how your CRO actually approves controls, how your Head of Expansion actually picks the next market, and what your auditors actually want to see.
- Three columns:
  - **Pilot** — one team, one new jurisdiction, measure weeks → days.
  - **Co-design** — interview-driven user stories with CRO + Expansion.
  - **Scale** — multi-tenant rollout across Bunq's full expansion roadmap.
- Visual: bottom-right CTA *"Build it with us."* Footer: team name + contact.

### 14.3 Spoken script (latest revision)

**1 — Intro / Pain (≈45 sec)** *(canonical — Amsterdam-discovery framing)*

> For this hackathon we wanted to build something that actually matters. So we got on a plane to Amsterdam, walked into Bunq Update, and sat down with **Kris Wulteputte** and people from Bunq's Risk Operations, Expansion, and compliance teams.
>
> Different roles, different desks — but every single conversation came back to the same sentence: *"It just… takes… so long."*
>
> Launching one new jurisdiction means weeks of reading regulations, mapping every obligation to an internal control by hand, chasing evidence, and rebuilding the audit trail from scratch. Not weeks of engineering — weeks of humans with spreadsheets. Kris and the team made that pain real for us, in their own words.
>
> So we did the research on how Bunq's competitors handle this. The ones that expand fastest — Revolut being the obvious example — aren't faster because of marketing. They're faster because they built an internal compliance machine. Every other fintech is paying for that gap in time.

**2 — Bridge to demo (1 sentence)**

> We thought: what if a Head of Expansion could collapse those weeks into one screen? Let us show you.

**3 — [LIVE DEMO]**

**4 — Closing — what we built (≈20 sec)**

> What you just saw is a fully functional MVP, built end-to-end on the Bunq stack — Bedrock, S3 Vectors, DynamoDB, Fargate. Not a prototype, not a mock. The pipeline runs, the proof pack downloads, the audit log is hash-chained.
>
> *(Slide with architecture / logos.)*

**5 — What's next (≈40 sec)**

> This week we proved the engine works. Next, we make it yours.
>
> We want to sit down with Kris and Bunq's compliance and expansion teams again — same as we did at Bunq Update — but this time co-designing around your real user stories: how Kris and Risk Operations actually approve a control, how your Head of Expansion actually picks the next country, what your auditors actually want to see in the proof pack.
>
> The dream outcome: Bunq doesn't open a new jurisdiction in quarters. You open it in weeks — sometimes days. And every Monday morning, your CRO opens a single dashboard and sees, with cryptographic evidence, that the bank is fully compliant across every market it operates in. Not "we think so." Provable.
>
> That's what we're here to build with you.

### 14.4 Key talking-point anchors (memorise)

- **The pilgrimage:** we flew to **Amsterdam**, to **Bunq Update**, and sat down with **Kris Wulteputte** and his team. Lead with this — it's the credibility hook. We didn't imagine the problem; we heard it from the people living it.
- **The line:** *"It just… takes… so long."* (verbatim from those interviews; never paraphrase it on stage.)
- **The frame:** Revolut's moat is compliance speed, not marketing.
- **The proof:** hash-chained audit log + downloadable proof pack = "provable, not we-think-so."
- **The ask:** another working session with Kris + Risk Ops + Head of Expansion → pilot → multi-tenant.
- **The numbers to say out loud:** 20× pipeline speedup; 1,200+ regulation PDFs indexed; runs end-to-end on Bunq's existing AWS stack.

### 14.5 Why the Amsterdam story matters (don't drop it)

Most hackathon teams pitch a problem they imagined at a desk. We pitch a problem **we heard, in person, from the team that owns it.** That single fact is what separates this deck from every other "AI for compliance" pitch. Always:

- Name **Kris Wulteputte** at least once on Slide 1 and once in the spoken intro — it grounds the story.
- Mention **Bunq Update** by name (the offsite where we met them) — it shows we did the homework, not the homework-of-the-homework.
- Say *"different roles, different desks — same sentence"* — that line lands because it's true.
- Close with *"build it with **you**, not **for** you"* — references the discovery loop and signals partnership, not pitch.

---

End of context.
