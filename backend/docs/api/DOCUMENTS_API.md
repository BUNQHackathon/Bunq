# Documents API — frontend contract

**Status:** live — this is the current contract.

## Why this exists

Documents (regulations, policies, briefs, audio) are now **deduped globally by SHA-256 content hash**. A user who has uploaded GDPR once can attach it to any number of new sessions without re-uploading or re-OCR'ing. Textract output and extraction results (obligations, controls) are cached on the Document row and reused across sessions.

Semantically:
- **Session** = an analysis run. Has a *list of attached Document IDs*, its own gaps, sanctions hits, narrative, report.
- **Document** = a file the user has uploaded at least once, keyed by content hash. Shared across sessions.

---

## Breaking changes

| Old | New | Migration |
|---|---|---|
| `POST /api/v1/sessions/{id}/upload` — session-scoped PUT presign | `POST /api/v1/documents/presign` + `POST /api/v1/documents/finalize` + `POST /api/v1/sessions/{sessionId}/documents/{documentId}` | Replace 1-step upload with 3-step: presign → upload → finalize → attach |
| `GET /api/v1/sessions/{id}/uploads` — session's uploaded files | `GET /api/v1/sessions/{id}` response now includes `documentIds: string[]`; resolve each via `GET /api/v1/documents/{documentId}` | Two calls instead of one; allows parallel fetch |
| `GET /api/v1/documents` — was the Bedrock KB regulation browser | `GET /api/v1/kb/regulations` (renamed) | Update 1 URL |
| `GET /api/v1/documents/{id}` — was KB doc detail | `GET /api/v1/kb/regulations/{id}` | Update 1 URL |
| `Session.uploads: UploadRecord[]` shape in JSON | `Session.documentIds: string[]` | Replace field |

---

## New endpoints

### `POST /api/v1/documents/presign`

Request a temporary S3 PUT URL for a new upload.

**Body:**
```json
{
  "filename": "gdpr.pdf",
  "contentType": "application/pdf"
}
```

**200 response:**
```json
{
  "incomingKey": "documents/incoming/a3f2b9c1-...ext",
  "uploadUrl": "https://launchlens-uploads.s3.eu-central-1.amazonaws.com/...signed...",
  "expiresInSeconds": 900
}
```

**Client uploads the file.** The presigned URL includes `x-amz-sdk-checksum-algorithm=SHA256` in its signed headers, which means the PUT must supply both the algorithm name AND the computed hash. Browser `fetch()` sends flat bodies, so the hash has to be computed client-side (single `crypto.subtle.digest` call — no library):

```js
const hashBuffer = await crypto.subtle.digest('SHA-256', await file.arrayBuffer());
const hashBase64 = btoa(String.fromCharCode(...new Uint8Array(hashBuffer)));

await fetch(uploadUrl, {
  method: 'PUT',
  body: file,
  headers: {
    'Content-Type': contentType,
    'x-amz-sdk-checksum-algorithm': 'SHA256',
    'x-amz-checksum-sha256': hashBase64,
  },
});
```

S3 verifies the supplied hash against the received bytes and stores it as object metadata. The backend reads it back in `finalize` via `HeadObject` + `ChecksumMode.ENABLED` — **no PDF bytes ever transit the backend JVM**.

---

### `POST /api/v1/documents/finalize`

Register the uploaded file as a Document. Backend reads the server-side SHA-256 from S3, dedupes against the Documents table, and returns the canonical `Document` record.

**Body:**
```json
{
  "incomingKey": "documents/incoming/a3f2b9c1-...ext",
  "filename": "gdpr.pdf",
  "contentType": "application/pdf",
  "kind": "regulation"
}
```

`kind` ∈ `"regulation" | "policy" | "brief" | "evidence" | "audio" | "other"`.

**200 response (new upload):**
```json
{
  "document": {
    "id": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
    "filename": "gdpr.pdf",
    "contentType": "application/pdf",
    "sizeBytes": 2841523,
    "kind": "regulation",
    "firstSeenAt": "2026-04-25T18:22:11Z",
    "lastUsedAt": "2026-04-25T18:22:11Z",
    "extractedText": null,
    "extractedAt": null,
    "pageCount": null,
    "obligationsExtracted": false,
    "controlsExtracted": false
  },
  "deduped": false
}
```

**200 response (dedupe — content already in library):**
```json
{
  "document": { "id": "e3b0c442...", /* full existing record */ },
  "deduped": true
}
```

When `deduped: true`, the temporary upload at `incomingKey` is deleted by the backend. The client should show "already in library, using existing" and proceed to attach.

---

### `GET /api/v1/documents?kind=&limit=`

List the user's document library. Results sorted by `lastUsedAt desc`.

**Query params:**
- `kind` (optional) — filter by kind
- `limit` (optional, default 50) — max items

**200 response:**
```json
{
  "documents": [
    { "id": "e3b0c442...", "filename": "gdpr.pdf", "kind": "regulation", "sizeBytes": 2841523, "lastUsedAt": "2026-04-25T18:22:11Z", "pageCount": 156, "obligationsExtracted": true, "controlsExtracted": false },
    { "id": "5feceb66...", "filename": "bunq_privacy.pdf", "kind": "policy", "sizeBytes": 184201, "lastUsedAt": "2026-04-24T09:10:02Z", "pageCount": 12, "obligationsExtracted": false, "controlsExtracted": true }
  ],
  "nextCursor": null
}
```

The `extractedText` field is omitted from list results; fetch the full Document for it.

---

### `GET /api/v1/documents/{id}`

Full Document detail, including `extractedText` (can be large — 5–10 MB for a 400-page reg).

**200 response:** same shape as the `document` field in finalize response.

**404** if the hash is not in the library.

---

### `POST /api/v1/sessions/{sessionId}/documents/{documentId}`

Attach an existing Document to a Session.

**No body required.**

**200 response:**
```json
{
  "sessionId": "sess-abc",
  "documentIds": ["e3b0c442...", "5feceb66..."]
}
```

Idempotent — re-attaching is a no-op.

Backend updates `Document.lastUsedAt` to now.

---

### `DELETE /api/v1/sessions/{sessionId}/documents/{documentId}`

Detach a Document from a Session. Does NOT delete the Document row itself (other sessions may still reference it).

**204 No Content.**

---

## Session shape changes

`GET /api/v1/sessions/{id}` response:

**Before:**
```json
{
  "id": "sess-abc",
  "state": "CREATED",
  "uploads": [{ "s3Key": "sessions/sess-abc/uploads/foo.pdf", "filename": "gdpr.pdf", "...": "..." }],
  "..."
}
```

**After:**
```json
{
  "id": "sess-abc",
  "state": "CREATED",
  "documentIds": ["e3b0c442...", "5feceb66..."],
  "..."
}
```

To display attached files, frontend fans out to `GET /api/v1/documents/{id}` per ID (parallel) or uses a new batch endpoint if added later.

---

## Upload flow (end-to-end)

```
1. POST /api/v1/documents/presign
   body: {filename, contentType}
   → {incomingKey, uploadUrl, expiresInSeconds}

2. PUT <uploadUrl> with file bytes
   (S3 computes SHA-256 server-side)

3. POST /api/v1/documents/finalize
   body: {incomingKey, filename, contentType, kind}
   → {document, deduped}

4. POST /api/v1/sessions/{sessionId}/documents/{document.id}
   → {sessionId, documentIds}
```

If the frontend already has the hash (e.g. the user picked from a library grid), skip steps 1–3:

```
POST /api/v1/sessions/{sessionId}/documents/{knownDocumentId}
```

---

## Performance expectations

| Scenario | Wall-clock |
|---|---|
| First upload of a 150-page PDF, cold pipeline run | Textract ~60–90s, Bedrock extraction ~20–40s, total ~2–3 min |
| Re-attaching the same Document to a new session, pipeline run | **No Textract, no Bedrock extraction for obligations/controls** — instant cache clone. Gap + mapping + sanctions still run fresh (session-scoped). Total ~10–20s. |
| Same document, same session | Not applicable — idempotent attach is a no-op |

The cache hit story is the demo's "second run is instant" moment. Frontend should surface `Document.obligationsExtracted` / `controlsExtracted` flags in the library picker so users can see which docs will trigger heavy work.

---

## SSE events (unchanged + additions)

Existing per-record events stay: `obligation.extracted`, `control.extracted`, `mapping.computed`, `gap.identified`, `sanctions.hit`, `ground_check.verified`, `ground_check.dropped`, `narrative.completed`.

**New:**
```
event: document.cached
data: {"documentId": "e3b0c442...", "kind": "regulation", "recordsReused": 47}
```

Emitted once per Document per stage when a cache hit skips Bedrock. Gives the UI an explicit "filled from cache" signal — renders instantly with a badge.

```
event: document.extracted
data: {"documentId": "5feceb66...", "kind": "regulation", "pageCount": 156, "extractedAt": "..."}
```

Emitted once per Document after Textract completes (first-time ingest). Allows the UI to update the document card with page count + "text ready" state mid-pipeline.

---

## Error responses

All errors follow the existing shape:
```json
{ "message": "...", "correlationId": "..." }
```

Document-specific status codes:
- `404` — documentId not in library, or incomingKey not found in S3
- `409` — finalize called twice for the same incomingKey (idempotency — return the existing Document)
- `413` — upload exceeded max size (500 MB, matching Textract job limit)
- `422` — kind value not recognized, or contentType doesn't match actual file bytes

---

## Not in this refactor

- Cross-user sharing or permissions (single-tenant demo)
- Document versioning (replacing `gdpr.pdf` v1 with v2 — current model treats them as independent rows since hashes differ)
- Document delete (`DELETE /api/v1/documents/{id}`) — add later if needed; sessions-level detach is enough for the demo
- Thumbnail generation
- Text search across the library

---

## Open question

Should `GET /api/v1/documents/{id}` return `extractedText` inline, or require a separate `GET /api/v1/documents/{id}/text` to fetch it? 10 MB per call is heavy. Current decision: inline with a warning — change if the frontend complains.
