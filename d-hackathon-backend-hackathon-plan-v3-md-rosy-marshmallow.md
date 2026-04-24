# LaunchLens Hackathon — 3 Features (Chat · Launches · Jurisdictions)

## Context

Hackathon submission is imminent. The backend (`D:\hackathon\backend\java-backend`) has the compliance engine working: `Launch → JurisdictionRun → Session → (obligations/controls/mappings/gaps/sanctions/evidence/proof-pack.zip)`. The frontend (`D:\hackathon\Bunq\frontend`) has a polished **globe.gl + D3 world map**, a **D3 force graph**, and stub pages — but no backend wiring.

We ship **three user-facing features**, all reading from the same domain model, all sharing one reusable **ComplianceGraph** component:

1. **Chat** (`/chat`) — user asks a compliance question, backend streams a textual answer AND a live compliance graph that builds as nodes are cited (obligation → control → gap).
2. **Launches** (`/launches`) — user describes a feature they want to ship and picks target countries. Backend runs compliance analysis per country; UI shows world-map verdicts + per-country answer ("can ship" or "must change X first") + graph per country.
3. **Jurisdictions** (`/jurisdictions`) — user picks a country on the map. UI shows three columns: **Keep** (can integrate as-is), **Modify** (feasible with changes — list the changes), **Drop** (block / too much divergence).

`Launch.kind` enum (`PRODUCT | POLICY | PROCESS`) makes each Launch a universal "compliance object", matching Revolut's operational model. Sessions disappear from the UI but stay alive internally.

Three teammates, parallel tracks. API contract below is the hard boundary so Tracks B and C start immediately against mocks without waiting for A.

---

## The three features in detail

### Feature 1 — Chat with live compliance graph

**User flow:** user types *"Can bunq offer a crypto debit card in NL?"* → chat streams a text answer while a right-hand panel builds a compliance graph: MiCA Art 75 (obligation) → Sanctions Screening Service (control) → OFAC list missing (gap, red). Clicking a node shows details.

**How it works (Option B — full pipeline reasoning):**
- Backend endpoint `POST /chat/with-graph` (or augmented SSE on existing `/chat`) does:
  1. **RAG retrieval** over `launchlens-obligations` + `launchlens-controls` filtered by jurisdiction mentioned in the question (existing `JurisdictionInference`)
  2. **Mapping pass** — reuse existing `MappingComputeService` to match retrieved obligations ↔ retrieved controls (produces `maps_to` edges + confidence)
  3. **Gap pass** — reuse existing `GapAnalyzeStage` logic on uncovered obligations (produces gap nodes with severity, residual risk, recommended action)
  4. **LLM answer synthesis** — Bedrock call with the retrieved+mapped items as context, streams text
- Response shape: SSE with event types `token` (text chunk) and `graph_node` / `graph_edge` (incremental graph updates)
- No document ingestion — uses pre-indexed KB. Target latency: 10–30s (vs 2–5min for a full Session pipeline)

**Demo value:** *"Instead of a black-box LLM answer, we show the regulatory reasoning as a graph. Every claim is backed by a node you can click."*

### Feature 2 — Launches (Q+A + per-country verdicts)

**User flow:** user fills one form: *question/feature description* (textarea) + *kind* (PRODUCT/POLICY/PROCESS radio) + *target countries* (checkboxes from NL/DE/FR/UK/US/IE). Submit → backend creates a Launch + a JurisdictionRun per country, fires the pipeline, returns launch ID. UI redirects to `/launches/:id` showing world map (countries tinted by verdict as runs complete) + per-country drawer with answer + ComplianceGraph.

**No wizard** — single page, single form. Pipeline is async (2–5min), UI polls `GET /launches/:id` every 5s.

**Per-country drawer contents** (on map click):
- Verdict 🟢 *Can ship as-is* / 🟡 *Can ship after changes* / 🔴 *Blocked — requires major work*
- For 🟡: bulleted list of required changes pulled from `Gap.recommendedActions`
- For 🔴: bulleted list of hard blockers
- **Download Proof Pack** button
- Inline **ComplianceGraph** for that JurisdictionRun

### Feature 3 — Jurisdictions (3-column triage)

**User flow:** `/jurisdictions` shows world map heatmap (aggregate verdict per country). Click a country → `/jurisdictions/:code` with **three kanban-style columns**:

| Column | Criteria | Card content |
|---|---|---|
| **🟢 Keep** | `verdict = GREEN` | Launch name + kind badge + "Ready to integrate" |
| **🟡 Modify** | `verdict = AMBER` | Launch name + kind badge + list of changes (from `Gap.recommendedActions[]` joined) |
| **🔴 Drop** | `verdict = RED` | Launch name + kind badge + reason (from `Gap.narrative` of highest-severity gap) |

Each card has a 📊 icon → opens the ComplianceGraph for that (launch, jurisdiction) pair in a modal or drill-in page.

**Demo value:** *"One glance at NL: bunq knows exactly which of its 50 compliance objects can migrate there as-is, which need tweaks, and which are blocked."*

### Shared across all 3 — ComplianceGraph component

- **Nodes**: `obligation` (blue) · `control` (green) · `gap` (red, severity-scaled size, pulses) · `evidence` (gray, small)
- **Edges**: `maps_to` (solid, thickness = mapping confidence) · `has_gap` (dashed red) · `evidenced_by` (thin solid) · `remediated_by` (dotted)
- **Interactions**: hover → tooltip · click obligation → regulation quote · click control → owner+cadence · click gap → severity dimensions + recommended action + deadline · click evidence → presigned S3 GET in new tab · drag/zoom/pan (already working in `GraphPage.tsx`)
- **Sources**:
  - Chat: SSE-streamed from `/chat/with-graph`
  - Launches/Jurisdictions: `GET /launches/{id}/jurisdictions/{code}/compliance-map` (static one-shot)
- **Graceful empty**: if pipeline still running, placeholder *"Analysis in progress — graph will populate"*

---

## Demo jurisdictions (frozen)

**NL, DE, FR, UK, US, IE** — NL (bunq home), DE/FR (core EU), UK (post-Brexit EMI divergence), US (broker-dealer expansion path), IE (fintech gateway).

---

## Decisions locked

| # | Decision | Rationale |
|---|---|---|
| 1 | `Launch.kind` enum (`PRODUCT\|POLICY\|PROCESS`, default `PRODUCT`) | One field turns Launch into universal compliance object. No new table. |
| 2 | Sessions hidden from UI only | Delete `SessionController` HTTP routes + frontend mentions. Keep Session class/tables/pipeline intact. Zero-risk. |
| 3 | GSI `jurisdiction-index` on `launchlens-jurisdiction-runs` (PK `jurisdiction_code`, SK `launch_id`) | Needed for Jurisdictions mode queries. |
| 4 | 6-country demo set: NL/DE/FR/UK/US/IE | Supports the "core + adjacent + expansion" story the judges want. |
| 5 | Chat uses full RAG+mapping+gap reasoning (Option B), not just citations | Stronger demo: graph IS the argument, not decoration. Reuses existing MappingComputeService and GapAnalyzeStage. 10–30s latency acceptable. |
| 6 | Jurisdictions = 3-column kanban (Keep/Modify/Drop), NOT a matrix table | Visual narrative clearer than a table. |
| 7 | Launches uses single-page form, NOT a 3-step wizard | Simpler, faster to build, better demo flow. |
| 8 | Single reusable `ComplianceGraph` React component for all 3 features | Build once (Track B owns), used by all three pages. |

---

## Data model — minimal changes

```
Launch                             ← ADD kind field
  id, name, brief, license, counterparties[],
  kind: PRODUCT|POLICY|PROCESS  ← NEW (default PRODUCT)
  status, createdAt, updatedAt

JurisdictionRun                    ← ADD GSI only
  PK: launchId, SK: jurisdictionCode
  currentSessionId, verdict, gapsCount, sanctionsHits,
  proofPackS3Key, lastRunAt, status
  GSI: jurisdiction-index (PK: jurisdictionCode, SK: launchId)  ← NEW

Session / Obligation / Control / Mapping / Gap / Sanction / Evidence / Document  ← UNCHANGED
```

No migrations needed.

---

## API contract (hard boundary)

All routes under `/api/v1`.

### Launches (Feature 2)

```
POST   /launches                                            // kind now required
  body: { name, brief, license, kind, jurisdictions: ["NL","DE",...] }
  → 201 { id, name, kind, counterparties[], jurisdictionRuns: [...RUNNING...] }
  // IMPLEMENTATION NOTE: the backend creates all JurisdictionRuns in one call.
  // Saves a round-trip vs current "POST launch, then POST each jurisdiction".

GET    /launches
  → 200 [{ id, name, kind, jurisdictionCount, aggregateVerdict, updatedAt }]

GET    /launches/{id}
  → 200 { id, name, brief, license, kind, counterparties[],
          jurisdictionRuns: [{ jurisdictionCode, verdict, gapsCount, sanctionsHits,
                               status, lastRunAt, proofPackAvailable,
                               summary: "Can ship as-is" | "Requires changes" | "Blocked",
                               requiredChanges: [str],  // from Gap.recommendedActions, only if AMBER
                               blockers: [str] }]       // only if RED

POST   /launches/{id}/jurisdictions/{code}/run              // re-run one market
  → 202 { launchId, jurisdictionCode, status: "RUNNING" }

GET    /launches/{id}/jurisdictions/{code}/proof-pack       // ZIP
  → 200 application/zip
```

### Jurisdictions (Feature 3) — NEW

```
GET    /jurisdictions                                       // map heatmap
  → 200 [{ code: "NL", aggregateVerdict: "AMBER", launchCount: 4, worstVerdict: "RED" }]

GET    /jurisdictions/{code}/triage                         // 3-column content
  → 200 { code,
          keep:   [{ launchId, name, kind }],               // verdict=GREEN
          modify: [{ launchId, name, kind, changes: [str] }],  // verdict=AMBER, changes from Gap.recommendedActions
          drop:   [{ launchId, name, kind, reason: str }]   // verdict=RED, reason from worst-gap narrative
        }
```

### Compliance graph (shared by Launches + Jurisdictions)

```
GET    /launches/{id}/jurisdictions/{code}/compliance-map
  → 200 { nodes: [{ id, type: obligation|control|gap|evidence, label,
                    metadata: { ... } }],
          edges: [{ source, target, type: maps_to|has_gap|evidenced_by|remediated_by,
                    confidence?: 0..1 }] }
  // Implementation: look up JurisdictionRun.currentSessionId,
  // delegate to existing evidenceController.getComplianceMap(sessionId).
```

### Chat with graph (Feature 1) — NEW / augmented

```
POST   /chat/with-graph                                     // SSE response
  body: { question: str, jurisdictionHint?: str }
  → 200 text/event-stream, events:
       event: token         data: { text: "..." }                    // incremental LLM text
       event: graph_node    data: { id, type, label, metadata }      // as retrieval+mapping fires
       event: graph_edge    data: { source, target, type, confidence }
       event: done          data: { chatId, finalGraph: {nodes,edges} }

  // Implementation pipeline (inside ChatWithGraphService):
  //   1. JurisdictionInference on question
  //   2. RAG retrieve top-N obligations + controls
  //   3. MappingComputeService on retrieved pairs → edges
  //   4. GapAnalyzeStage (session-less variant) on uncovered obligations → gap nodes
  //   5. Bedrock stream with context=(obligations,controls,gaps)
  //   6. Emit graph events BEFORE first token, then token events, then done
```

### DELETED (session hide)
```
POST   /sessions                    ← delete controller
GET    /sessions                    ← delete
GET    /sessions/{id}               ← delete
POST   /sessions/{id}/pipeline/start        ← delete (pipeline kicked off by LaunchService)
GET    /sessions/{id}/report.pdf            ← delete (replaced by proof-pack)
// Keep internally: SessionService, SessionRepository, Session model, SSE events endpoint
```

---

## Parallel tracks — three teammates

All three start at hour 0. Track A publishes `MOCK_API.md` first to unblock B and C.

---

### Track A — Backend (Teammate A)
**Scope:** `Launch.kind`, GSI, Jurisdictions triage endpoint, compliance-map wrapper, **chat-with-graph service**, hide sessions, seed script. Java Spring Boot 4, AWS SDK v2, DynamoDB Enhanced Client. Base: `D:\hackathon\backend\java-backend\src\main\java\com\bunq\javabackend`.

**A1. Mock fixtures (30 min, FIRST)**
- Create `D:\hackathon\backend\MOCK_API.md` with sample JSON for every endpoint (6 countries × 3 seeded launches × compliance-map sample × chat SSE log). Commit immediately.

**A2. `Launch.kind` field (45 min)**
- New enum `model/launch/LaunchKind.java`: `PRODUCT, POLICY, PROCESS`.
- Add `kind` field to `Launch.java` (default `PRODUCT`). Use `@DynamoDbConvertedBy` pattern from `SeverityConverter`.
- Update `CreateLaunchRequestDTO` to accept `kind` + `jurisdictions: List<String>`.
- Update `LaunchService.createLaunch` — accept kind, also create all JurisdictionRuns in same call (loop `addJurisdiction` for each code in request).
- Update `LaunchResponseDTO` / `LaunchSummaryDTO` to include `kind`.
- **DoD:** `POST /launches {"name":"X","kind":"POLICY","jurisdictions":["NL","DE"]}` creates launch + 2 JurisdictionRuns in RUNNING state.

**A3. GSI `jurisdiction-index` (1h)**
- Terraform: add GSI to `launchlens-jurisdiction-runs` table definition.
- `JurisdictionRun.java`: `@DynamoDbSecondaryPartitionKey(indexNames="jurisdiction-index")` on `jurisdictionCode`, `@DynamoDbSecondarySortKey` on `launchId`.
- `JurisdictionRunRepository.findByJurisdiction(String code)`.
- **DoD:** query by `jurisdictionCode="NL"` returns all runs across launches.

**A4. Jurisdictions endpoints (2h)**
- New `JurisdictionsOverviewController.java`:
  - `GET /jurisdictions` → group by code, aggregate verdict (worst-of).
  - `GET /jurisdictions/{code}/triage` → partition runs by verdict into `keep`/`modify`/`drop`. For `modify`, load Gaps for each run and extract `recommendedActions`. For `drop`, load highest-severity Gap and use its `narrative`.
- New `service/JurisdictionOverviewService.java` with the bucketing logic.
- **DoD:** `/jurisdictions/NL/triage` returns 3 columns populated for seeded data.

**A5. Compliance-map wrapper (45 min)**
- New route on `LaunchController`: `GET /launches/{id}/jurisdictions/{code}/compliance-map`.
- Look up `JurisdictionRun.currentSessionId`, delegate to existing compliance-map logic (extract from `EvidenceController` into a `ComplianceMapService` first).
- **DoD:** returns nodes+edges JSON.

**A6. Chat-with-graph service (3h — the new big piece)**
- New `service/ChatWithGraphService.java`. Pipeline:
  1. `JurisdictionInference.infer(question)` → optional jurisdiction code
  2. `RagService.retrieveObligations(question, jurisdiction, top=8)` — reuse existing retrieval; return `List<Obligation>`
  3. `RagService.retrieveControls(question, top=8)` — reuse
  4. `MappingComputeService.matchPairs(obligations, controls)` — **refactor** `MappingComputeService` if needed so it accepts direct lists (not session-bound). Returns `List<Mapping>` with confidence.
  5. `GapAnalyzeStage.scoreUncovered(obligations, mappings)` — **refactor** to session-less variant. Returns `List<Gap>` for uncovered obligations.
  6. Assemble graph: nodes = obligations + controls + gaps; edges = mappings (as `maps_to`) + gap links (as `has_gap`).
  7. Emit SSE `graph_node` + `graph_edge` events first, then call Bedrock chat with context and stream `token` events, then `done`.
- New `controller/ChatWithGraphController.java`: `POST /chat/with-graph` SSE.
- **Refactor needed**: `MappingComputeService` and `GapAnalyzeStage` currently assume session context. Extract session-less inner methods.
- **DoD:** `curl -N -X POST /chat/with-graph -d '{"question":"can we do crypto card in NL?"}'` streams graph events within 3s, then text tokens, completes <30s.

**A7. Hide sessions (30 min)**
- Delete `SessionController.java`, `ReportController.java`. In `PipelineController`, delete start route (pipeline only kicked off by `LaunchService`). Keep SSE events endpoint as internal.
- **DoD:** `GET /api/v1/sessions` → 404. Full launch flow still works.

**A8. Seed script (1h)**
- `D:\hackathon\backend\scripts\seed-demo.sh`: create 3 launches via REST.
  - Crypto Debit Card (PRODUCT) → NL, DE, FR, UK, US
  - ToC §5.3 Sanctions Screening (POLICY) → NL, DE, FR, IE
  - KYC Onboarding Flow (PROCESS) → NL, DE, UK, US, IE
- **DoD:** `GET /launches` returns 3, `GET /jurisdictions` returns 6.

**Total Track A: ~9h. Critical path. A6 is the riskiest — budget accordingly.**

**Files modified/created:**
- `model/launch/Launch.java`, new `LaunchKind.java`, new `LaunchKindConverter.java`
- `model/launch/JurisdictionRun.java` (GSI annotations)
- `repository/JurisdictionRunRepository.java`
- `controller/LaunchController.java` (+ compliance-map route)
- New: `controller/JurisdictionsOverviewController.java`, `controller/ChatWithGraphController.java`
- Delete: `controller/SessionController.java`, `controller/ReportController.java`
- `service/LaunchService.java` (create jurisdictions in bulk)
- New: `service/JurisdictionOverviewService.java`, `service/ChatWithGraphService.java`, `service/ComplianceMapService.java`
- Refactor: `service/pipeline/MapObligationsControlsStage.java` (extract session-less matcher), `service/pipeline/GapAnalyzeStage.java` (extract session-less scorer)
- DTOs: `dto/request/CreateLaunchRequestDTO.java`, `dto/response/LaunchSummaryDTO.java`, `dto/response/LaunchResponseDTO.java`, new `dto/response/JurisdictionTriageDTO.java`, new `dto/response/ChatGraphEventDTO.java`
- Terraform: add GSI to `launchlens-jurisdiction-runs`
- New: `scripts/seed-demo.sh`, `MOCK_API.md`

---

### Track B — Frontend shared infra + Launches + ComplianceGraph (Teammate B)
**Scope:** API client, types, shared components (ModeToggle, VerdictPill, KindBadge, WorldMapD3, **ComplianceGraph**), Launches page + detail. Base: `D:\hackathon\Bunq\frontend\src`. React 19 + Vite + TS + Tailwind 3.

**B1. API client + types + mock (1h, FIRST — unblocks C)**
- New `src/api/client.ts`: typed `fetch` wrapper + SSE helper for `/chat/with-graph`. Env `VITE_API_BASE` (default `http://localhost:8080/api/v1`).
- New `src/api/types.ts`: interfaces for every entity in the contract (`Launch`, `LaunchKind`, `JurisdictionRun`, `Verdict`, `JurisdictionOverview`, `JurisdictionTriage`, `ComplianceGraph`, `ComplianceNode`, `ComplianceEdge`, `ChatGraphEvent`).
- New `src/api/mock.ts`: hardcoded fixtures derived from the contract (don't wait for MOCK_API.md).
- Services: `src/api/services/launches.ts`, `jurisdictions.ts`, `chat.ts`.
- **DoD:** `api.listLaunches()` + `api.streamChat(question)` typed and callable.

**B2. ModeToggle + shared primitives (1h)**
- `src/components/ModeToggle.tsx`: 3-segment `[💬 Chat] [🚀 Launches] [⚖️ Jurisdictions]`. Mounted in `App.tsx` as persistent header.
- `src/components/VerdictPill.tsx`: colored pill with traffic-light emoji.
- `src/components/KindBadge.tsx`: colored badge (PRODUCT=blue, POLICY=purple, PROCESS=amber).
- **DoD:** toggle visible on every page, navigates between `/chat`, `/launches`, `/jurisdictions`.

**B3. Shared `WorldMapD3` component (1h)**
- Extract D3 map logic from `JurisdictionsPage.tsx:129-200` into `src/components/WorldMapD3.tsx`.
- Props: `countries: { code: string; color: string; onClick?: () => void }[]`, `selectedCode?`, `highlightSet?: Set<string>`.
- **DoD:** reusable by LaunchDetailPage and JurisdictionsPage.

**B4. Shared `ComplianceGraph` component (2.5h — demo hero)**
- New `src/components/ComplianceGraph.tsx`. Ports D3 force simulation pattern from `GraphPage.tsx:36+`.
- Props: `graph: {nodes, edges}` (static mode) OR `sseUrl: string` (streaming mode for Chat).
- Node type → color map: obligation=blue, control=green, gap=red (severity-scaled), evidence=gray.
- Edge type → style: maps_to solid+thickness, has_gap dashed red, evidenced_by thin, remediated_by dotted.
- Interactions: hover tooltip, click → `onNodeClick(node)` callback for parent-owned drawer.
- Streaming mode: subscribe to SSE, append nodes/edges as they arrive, re-tick simulation.
- **DoD:** renders a static compliance-map payload; also renders streaming from a mock SSE source.

**B5. `/launches` Q+A form + list (1.5h)**
- Replace stub `pages/LaunchesPage.tsx` with split-view:
  - Top: **Q+A form** — textarea ("What do you want to ship?") + KindBadge radio + country checkboxes (NL/DE/FR/UK/US/IE). Submit → `api.createLaunch({name, brief, kind, jurisdictions})` → redirect to `/launches/:id`.
  - Below: list of prior launches as cards (name, kind badge, jurisdictionCount, aggregateVerdict pill).
- **DoD:** form submission creates launch + jurisdictions in one call, redirects to detail.

**B6. `/launches/:id` world map + per-country drawer + graph (2h)**
- New `pages/LaunchDetailPage.tsx`:
  - Header: name, kind badge, brief
  - Left 60%: `<WorldMapD3>` with the 6 jurisdictions tinted by verdict. RUNNING status = gray pulse.
  - Right 40%: when country clicked, drawer shows verdict, `summary`, `requiredChanges` bullets (AMBER) or `blockers` (RED), [⬇ Proof Pack], and an inlined `<ComplianceGraph>` loaded from `api.getComplianceMap(id, code)`.
- Poll `api.getLaunch(id)` every 5s while any run is RUNNING.
- **DoD:** seeded Crypto Card launch: click NL → drawer shows verdict 🟡 + 3 required changes + clickable graph + proof-pack download works.

**Total Track B: ~9h.**

**Files created/modified:**
- New: `src/api/client.ts`, `src/api/types.ts`, `src/api/mock.ts`, `src/api/services/{launches,jurisdictions,chat}.ts`
- New: `src/components/{ModeToggle,VerdictPill,KindBadge,WorldMapD3,ComplianceGraph}.tsx`
- New: `src/pages/LaunchDetailPage.tsx`
- Modified: `src/pages/LaunchesPage.tsx` (Q+A form + list), `src/App.tsx` (all routes, mount ModeToggle)

---

### Track C — Frontend Jurisdictions + Chat (Teammate C)
**Scope:** rewire `/jurisdictions` to real data, new `/jurisdictions/:code` 3-column triage, new `/chat` page with streaming graph. Base: same as Track B.

**Depends on Track B hour 1** for `src/api/*` + `ModeToggle` + `KindBadge` + `VerdictPill`. **Depends on Track B hour 4.5** for `ComplianceGraph`. Until then, Track C mocks these with local stubs.

**C1. Refactor `/jurisdictions` to regulator heatmap (2h)**
- `pages/JurisdictionsPage.tsx`: replace hardcoded `countryStatus`/`countryDetails` (lines 7–53) with `api.getJurisdictionsOverview()`.
- Map `aggregateVerdict` → country color (GREEN/AMBER/RED/inactive).
- Keep globe+2D toggle (already built).
- Click country → `navigate('/jurisdictions/' + code)`.
- Sidebar: `launchCount`, `worstVerdict`, "Open detail →" button.
- **DoD:** 6 demo countries tinted from real data; clicking NL routes to `/jurisdictions/NL`.

**C2. `/jurisdictions/:code` 3-column triage (2.5h)**
- New `pages/JurisdictionDetailPage.tsx`:
  - Header: country flag + name + aggregate verdict pill.
  - **Three kanban columns** (CSS grid, 3 equal cols):
    - 🟢 **Keep** — cards from `triage.keep[]`: launch name + KindBadge + "Ready to integrate"
    - 🟡 **Modify** — cards from `triage.modify[]`: name + KindBadge + `<ul>` of `changes`
    - 🔴 **Drop** — cards from `triage.drop[]`: name + KindBadge + `reason`
  - Each card has 📊 icon → opens modal with `<ComplianceGraph>` from `api.getComplianceMap(launchId, code)`. Modal also has [⬇ Proof Pack] button.
- Source: `GET /jurisdictions/{code}/triage`.
- **DoD:** `/jurisdictions/NL` shows three columns filled from real data; clicking 📊 shows graph in modal; proof pack downloads.

**C3. `/chat` with live streaming graph (3h — the Feature 1 demo)**
- New `pages/ChatPage.tsx`. Two-panel layout:
  - Left 50%: classic chat UI. Textarea at bottom, messages above. User message + assistant streaming text.
  - Right 50%: `<ComplianceGraph sseUrl="/api/v1/chat/with-graph?question=...">` streaming. Graph builds live as backend emits `graph_node` / `graph_edge` events.
- On submit: `api.streamChat(question)` returns an EventSource. Wire `token` events → append to text panel. Wire `graph_node` / `graph_edge` → append to graph state, trigger re-tick.
- Click graph node → left panel shows "The answer cited this: [node details]". Highlights the corresponding text span if present.
- Empty state: suggested questions (*"Can bunq offer a crypto debit card in NL?"*, *"Is our KYC flow compliant in Germany?"*).
- **DoD:** typing a question streams both text AND graph; graph nodes clickable; questions suggested.

**Total Track C: ~7.5h.**

**Files created/modified:**
- Modified: `pages/JurisdictionsPage.tsx`
- New: `pages/JurisdictionDetailPage.tsx`, `pages/ChatPage.tsx`
- Removed or repurposed: `pages/GraphPage.tsx` (logic now lives in `components/ComplianceGraph.tsx` — Track B owns)
- Modified: `src/App.tsx` (add routes; Track B set up the structure, C adds `/chat`, `/jurisdictions/:code`, `/jurisdictions/:code/launches/:id` if needed)

---

## Coordination

- **Hour 0:** A commits `MOCK_API.md`. B commits `src/api/{client,types,mock}.ts`. C starts C1 against B's mock.
- **Hour 2:** B has `WorldMapD3` + `ModeToggle` extracted. C imports.
- **Hour 4.5:** B has `ComplianceGraph` component done. C wires it into Chat and Jurisdictions detail.
- **Hour 6:** A's Chat-with-graph endpoint is live on staging. C flips from mock SSE to real.
- **Hour 8:** End-to-end demo rehearsal. A runs `seed-demo.sh`. B+C click through all three features.

**App.tsx ownership:** Track B owns all route registration (one edit point). C's new pages get stub routes added by B at hour 1, pointing at placeholders C will fill in.

---

## Demo script (~90s)

1. **[Chat]** Open `/chat`. Type *"Can bunq offer a crypto debit card in NL?"*. → text streams *"Yes, with caveats — MiCA Art 75 requires counterparty screening..."* while graph builds on the right: MiCA Art 75 obligation → Sanctions Screening Service control → red OFAC gap. Click the gap → "severity 0.82, fix by updating ToC §5.3". → *"Every claim backed by a clickable graph node."*
2. **[Launches]** Open `/launches`. Fill form: *"Crypto debit card"* + PRODUCT + check NL/DE/FR/UK/US. Submit → `/launches/:id` world map appears, countries light up as runs complete 🟢🟡🔴. Click NL 🟡 → drawer: *"Can ship after changes: update ToC §5.3, integrate OFAC list, add MiCA disclosures"* + ⬇ Proof Pack → ZIP downloads. → *"Compliance answer per-market, in minutes."*
3. **[Jurisdictions]** Toggle to `/jurisdictions`. Map heatmap. Click NL. → `/jurisdictions/NL` 3-column triage. → *"Keep: 2 features. Modify: 3 (here's exactly what to change). Drop: 1 (MiCA violation)."* Click 📊 on a Modify card → graph modal opens showing the reasoning. → *"One glance, triage-ready."*
4. Close. → *"One domain model, three user jobs, one reusable graph component as the audit trail."*

---

## Out of scope

- No Feature catalog entity — `Launch.kind` is enough.
- No Session rename in backend — only HTTP routes deleted.
- No new pipeline stages (reuse mapping + gap analyzer for chat via refactor, not duplication).
- No i18n, auth, mobile layout, dark mode.
- No real KB ingestion during hackathon — seed script uses existing fixtures.

---

## Verification

**Backend (Track A):**
```
curl -X POST /api/v1/launches -d '{"name":"Crypto Card","kind":"PRODUCT","brief":"...","license":"EMI","jurisdictions":["NL","DE"]}'
curl          /api/v1/launches/{id}                                 # kind + jurisdictionRuns[] w/ summary + requiredChanges
curl          /api/v1/jurisdictions                                 # 6 rows
curl          /api/v1/jurisdictions/NL/triage                       # {keep, modify, drop}
curl -o pp.zip /api/v1/launches/{id}/jurisdictions/NL/proof-pack    # valid ZIP
curl -N -X POST /api/v1/chat/with-graph -d '{"question":"can we do crypto card in NL?"}'
                                                                    # SSE: graph events first, then tokens, then done
curl          /api/v1/sessions                                      # 404
```

**Frontend:** `yarn dev`, click through the demo script against real backend. Record once, attach to submission.

**Critical files reference:**
- `D:\hackathon\backend\java-backend\src\main\java\com\bunq\javabackend\model\launch\Launch.java`
- `D:\hackathon\backend\java-backend\src\main\java\com\bunq\javabackend\model\launch\JurisdictionRun.java`
- `D:\hackathon\backend\java-backend\src\main\java\com\bunq\javabackend\controller\LaunchController.java`
- `D:\hackathon\backend\java-backend\src\main\java\com\bunq\javabackend\service\LaunchService.java`
- `D:\hackathon\backend\java-backend\src\main\java\com\bunq\javabackend\service\ProofPackService.java` (already done)
- `D:\hackathon\backend\java-backend\src\main\java\com\bunq\javabackend\service\pipeline\MapObligationsControlsStage.java` (needs session-less extraction for A6)
- `D:\hackathon\backend\java-backend\src\main\java\com\bunq\javabackend\service\pipeline\GapAnalyzeStage.java` (needs session-less extraction for A6)
- `D:\hackathon\backend\java-backend\src\main\java\com\bunq\javabackend\service\ChatService.java` (reference for SSE streaming pattern)
- `D:\hackathon\Bunq\frontend\src\App.tsx`
- `D:\hackathon\Bunq\frontend\src\pages\JurisdictionsPage.tsx` (extract D3 map)
- `D:\hackathon\Bunq\frontend\src\pages\GraphPage.tsx` (extract force-sim pattern into ComplianceGraph)
- `D:\hackathon\Bunq\frontend\src\pages\LaunchesPage.tsx` (stub to replace)
