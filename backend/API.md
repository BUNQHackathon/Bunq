# API Contract

All routes are under `/api/v1` (base path set in `application.yaml`). Controllers return `ResponseEntity<T>`. DTOs only — never expose model classes. Error shape: `{"message": "...", "correlationId": "..."}`.

For the full Documents library contract (new upload + dedup flow), see **`DOCUMENTS_API.md`**.
For the Java ↔ Python sidecar contract, see **`SIDECAR.md`**.

## Sessions

| Method | Path | Purpose |
|---|---|---|
| POST | `/sessions` | Create session → `{sessionId, state}` |
| GET | `/sessions/{id}` | Session detail: `{id, state, documentIds[], counterparties[], verdict, createdAt, updatedAt, ...}` |

Session state transitions are guarded in `SessionService.updateState`. Illegal transitions return `409 Conflict`. Allowed path: `CREATED → UPLOADING → EXTRACTING → MAPPING → SCORING → SANCTIONS → COMPLETE`; any state may move to `FAILED`; `COMPLETE` and `FAILED` are terminal.

## Documents (user library)

See `DOCUMENTS_API.md` for the full contract. Summary:

| Method | Path | Purpose |
|---|---|---|
| POST | `/documents/presign` | Request temporary S3 PUT URL |
| POST | `/documents/finalize` | Dedup by SHA-256 → persist Document row |
| GET | `/documents?kind=&limit=` | Library listing |
| GET | `/documents/{id}` | Full Document (includes `extractedText`) |
| POST | `/sessions/{sessionId}/documents/{documentId}` | Attach |
| DELETE | `/sessions/{sessionId}/documents/{documentId}` | Detach |

Evidence uploads mirror this flow (presign + finalize) but persist to the Evidence table and accept a `mappingId` for cross-reference:

| Method | Path | Purpose |
|---|---|---|
| POST | `/sessions/{sessionId}/evidence/presign` | Request PUT URL |
| POST | `/sessions/{sessionId}/evidence/finalize` | Read S3 SHA-256, persist Evidence row |
| GET | `/evidence/{id}` | Evidence detail |

## Knowledge Base browser (seeded regulation corpus)

Read-only listing of the Bedrock KB source bucket (`launchlens-kb-regulations`).

| Method | Path | Purpose |
|---|---|---|
| GET | `/kb/regulations` | List KB regulation documents |
| GET | `/kb/regulations/{id}` | Document detail with presigned download URL + retrieved excerpts |

## Pipeline

| Method | Path | Purpose |
|---|---|---|
| POST | `/sessions/{id}/pipeline/start` | Kick off pipeline for a session (expects documents already attached) → `202 Accepted` |
| GET | `/sessions/{id}/events` (produces `text/event-stream`) | SSE stream for a session (path variable, not query param) |

Per-stage synchronous endpoints (`POST /obligations/extract`, `POST /controls/extract`, `POST /mappings/compute`, `POST /gaps/score`, `POST /sanctions/screen`) exist for debugging individual stages out of sequence — the primary entry point is `/sessions/{id}/pipeline/start`.

## Report

| Method | Path | Purpose |
|---|---|---|
| GET | `/sessions/{sessionId}/report.pdf` | 302 redirect to a 5-minute presigned GET URL for `reports/{sessionId}.pdf`; 404 if not generated yet |

The same URL is also emitted in the `pipeline.completed` SSE event's `reportUrl` field.

## Chat (RAG)

| Method | Path | Purpose |
|---|---|---|
| POST | `/chat` (produces `text/event-stream`) | Stream a Claude response grounded in the 3 KBs |
| GET | `/chat/{chatId}/history` | Past messages for a chat |
| POST | `/rag/query` | Non-streaming RAG query |
| POST | `/rag/query/stream` (SSE) | Streaming RAG query |

## Graph + proof tree

| Method | Path | Purpose |
|---|---|---|
| GET | `/graph` | Full compliance graph |
| GET | `/proof-tree/{mappingId}` | DAG for a specific mapping (proxied from sidecar) |
| GET | `/sessions/{id}/compliance-map` | Session-wide DAG (proxied from sidecar) |

## Per-resource list endpoints

Used by the frontend detail panes.

| Method | Path |
|---|---|
| GET | `/sessions/{id}/obligations` |
| GET | `/sessions/{id}/controls` |
| GET | `/sessions/{id}/mappings` |
| GET | `/sessions/{id}/sanctions` |
| GET | `/gaps/list?sessionId=` |

## Health + metadata

| Method | Path | Purpose |
|---|---|---|
| GET | `/actuator/health` | UP/DOWN per dependency (`sidecar`, `dynamo`, `s3`, `bedrock`) — served under the `/api/v1` context path like every other route |
| GET | `/jurisdictions` | Static list of supported jurisdictions |
| GET | `/files/presigned-url?s3Uri=...` | Presigned GET URL for an S3 object by URI |

## SSE stream

`GET /api/v1/sessions/{id}/events` — one EventSource per session. Heartbeat every 15 s. Frontend dispatches by `event:` name.

### Document lifecycle
```
event: document.extracted   — first-time Textract/Transcribe completed for a Document
event: document.cached      — cache hit on ingest or extraction; data includes recordsReused
```

### Per-record (drives the live graph)
```
event: obligation.extracted
event: control.extracted
event: mapping.computed     — data.metadata.route ∈ {"llm","cached"}
event: gap.identified       — includes residualRisk + 5 dimensions
event: sanctions.hit
event: ground_check.verified
event: ground_check.dropped
event: narrative.completed
```

### Stage lifecycle (dashboard state)
```
event: stage.started
event: stage.complete
event: stage.skipped
event: mapping.progress
event: sanctions.degraded   — sidecar unreachable, empty hits, pipeline continues
event: transcribe.polling   — audio ingest progress
event: ingest.polling       — PDF Textract progress
event: pipeline.completed   — includes reportUrl
event: pipeline.failed
```

## Error responses

| Status | Shape | Triggers |
|---|---|---|
| 400 | `{message, correlationId}` or `[{field: message}]` for validation | `IllegalArgumentException`, `@Valid` failure, malformed JSON |
| 404 | `{message, correlationId}` | `SessionNotFoundException`, `MappingNotFoundException`, `NotFoundException` |
| 409 | `{message, correlationId}` | Illegal state transition, `EntityAlreadyExistsException` |
| 502 | `{message: "Internal service unavailable", correlationId}` | `SidecarCommunicationException` (bubbles up when not caught in-stage) |
| 500 | `{message: "Unknown server error", correlationId}` | Unhandled `RuntimeException` |

Every error body includes `correlationId` when available (MDC-injected by a request filter).
