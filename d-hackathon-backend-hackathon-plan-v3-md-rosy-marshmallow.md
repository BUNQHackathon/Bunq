# LaunchLens Hackathon Pivot — Two-Mode UI + Feature Kind

## Context

Hackathon submission is today. The backend (`D:\hackathon\backend\java-backend`) already has the compliance engine working: `Launch → JurisdictionRun → Session → (obligations/controls/mappings/gaps/sanctions/evidence/proof-pack.zip)`. The frontend (`D:\hackathon\Bunq\frontend`) already has a beautiful **globe.gl + D3 world map** (`JurisdictionsPage`), a **D3 force graph** (`GraphPage`), and stub pages for the rest — but no backend wiring and no clear narrative.

We are pivoting to the **Revolut two-mode model** so the demo answers the two questions judges actually care about:

- **Regulator mode** — `/jurisdictions/:code` → "what's blocking us in NL?" → feature matrix (Crypto Card 🟡, ToC §5.3 🟢, KYC Flow 🔴) → proof pack.
- **Expansion mode** — `/launches/:id` → "can we launch Crypto in NL/DE/FR/UK/US/IE?" → world-map verdict matrix → per-country proof pack.

Both modes are **two readings of one domain model**. Sessions stay inside the backend but **disappear from the UI** — user never sees the word. We add one `Launch.kind` field (`PRODUCT|POLICY|PROCESS`) so a Launch models any compliance object (product feature, policy clause, process), matching how Revolut actually runs compliance.

Three teammates, ~8 hours, parallel execution. API contract below is the hard boundary between tracks so Track B and C can start immediately against a mock, without waiting for Track A.

---

## Target UX

### Top bar toggle (global)
`[🚀 Expansion] [⚖️ Regulator]` — visible on every page, switches default landing and tints the nav.

### Expansion mode
- `/launches` — list of launches (cards), each shows name + kind badge + market count + aggregate verdict
- `/launches/new` — 3-step wizard (name+kind → brief+license → target markets) → POST `/launches` then POST `/launches/{id}/jurisdictions/{code}` per market
- `/launches/:id` — **hero: world map**, countries tinted by per-jurisdiction verdict for this launch. Click country → right drawer with verdict, gap count, sanctions hits, **Download Proof Pack** button, and a link to open the proof-tree graph

### Regulator mode
- `/jurisdictions` — world map heatmap: each country tinted by **aggregate** verdict across all launches running there. Hover → count + worst-case. Click → go to `/jurisdictions/:code`
- `/jurisdictions/:code` — **feature matrix table**: one row per Launch with a JurisdictionRun in this country. Columns: Kind badge · Name · Verdict · Gaps · Sanctions · Last run · [Proof Pack]
- `/jurisdictions/:code/launches/:id` — proof-tree graph (repurposed `GraphPage`) sourced from the real compliance-map endpoint

### Demo jurisdictions (frozen set)
**NL, DE, FR, UK, US, IE** — covers the story: NL (bunq home/regulator anchor), DE/FR (core EU expansion), UK (post-Brexit EMI divergence), US (new-region broker-dealer path), IE (interesting market, common EU fintech gateway).

---

## Decisions locked

| # | Decision | Rationale |
|---|---|---|
| 1 | **Launch gets a `kind` field** (`PRODUCT | POLICY | PROCESS`, default `PRODUCT`) | Zero schema churn; one enum turns Launch into the universal "compliance object" — product feature OR policy clause OR internal process. No separate Feature table. |
| 2 | **Sessions hidden from UI only** | Delete `SessionController` HTTP routes + any frontend mention. Keep `Session` class, tables, pipeline plumbing, `JurisdictionRun.currentSessionId` intact. 30min change, zero regression risk. |
| 3 | **New GSI `jurisdiction-index` on `launchlens-jurisdiction-runs`** (PK: `jurisdiction_code`, SK: `launch_id`) | Needed for the Regulator mode queries: `GET /jurisdictions/{code}/launches`. |
| 4 | **Six-country demo set** (NL/DE/FR/UK/US/IE) | Tight enough to seed real data, broad enough for matrix + regulatory-divergence story. |
| 5 | **World map + compliance graph = demo hero artifacts** | Both already exist visually with mock data; wire them to the real API. |

---

## Data model — minimal changes

```
Launch                                      ← ADD kind field
  id, name, brief, license, counterparties[],
  kind: PRODUCT|POLICY|PROCESS  ← NEW (default PRODUCT)
  status, createdAt, updatedAt

JurisdictionRun                             ← ADD GSI only
  PK: launchId, SK: jurisdictionCode
  currentSessionId, verdict, gapsCount, sanctionsHits,
  proofPackS3Key, lastRunAt, status
  GSI: jurisdiction-index (PK: jurisdictionCode, SK: launchId)  ← NEW

Session                                     ← UNCHANGED (hidden from UI)
Obligation/Control/Mapping/Gap/Sanction/Evidence/Document  ← UNCHANGED
```

No migrations needed. `kind` is additive (existing rows read as default). GSI is additive.

---

## API contract (the hard boundary between tracks)

All routes under `/api/v1`. **Track A implements; Tracks B & C code against this contract with a mock API client from hour 0.**

### Launches (Expansion mode)

```
POST   /launches
  body: { name, brief, license, kind }                    // kind is NEW
  → 201 { id, name, kind, counterparties[], createdAt }

GET    /launches
  → 200 [{ id, name, kind, jurisdictionCount, aggregateVerdict, updatedAt }]

GET    /launches/{id}
  → 200 { id, name, brief, license, kind, counterparties[],
          jurisdictionRuns: [{ jurisdictionCode, verdict, gapsCount,
                               sanctionsHits, status, lastRunAt,
                               proofPackAvailable: bool }] }

POST   /launches/{id}/jurisdictions/{code}                // triggers pipeline
  → 202 { launchId, jurisdictionCode, status: "RUNNING" }

POST   /launches/{id}/jurisdictions/{code}/run            // re-run
  → 202 { ... }

GET    /launches/{id}/jurisdictions/{code}/proof-pack      // ZIP download
  → 200 application/zip
```

### Jurisdictions (Regulator mode) — NEW endpoints

```
GET    /jurisdictions                                      // map heatmap
  → 200 [{ code: "NL", aggregateVerdict: "AMBER",
           launchCount: 4, worstVerdict: "RED" }]          // one row per country that has ≥1 run

GET    /jurisdictions/{code}/launches                      // feature matrix
  → 200 { code, launches: [{ launchId, name, kind, verdict,
                              gapsCount, sanctionsHits, lastRunAt,
                              proofPackAvailable: bool }] }
```

### Proof-tree graph (Regulator drill-down)

```
GET    /launches/{id}/jurisdictions/{code}/compliance-map  // NEW wrapper
  → 200 { nodes: [{ id, type: obligation|control|gap|evidence, label, status }],
          edges: [{ source, target, type: maps_to|covers|has_gap|evidenced_by }] }
  // Implementation: look up JurisdictionRun.currentSessionId,
  // delegate to existing GET /sessions/{id}/compliance-map logic.
```

### DELETED (session hide)

```
POST   /sessions              ← delete
GET    /sessions              ← delete
GET    /sessions/{id}         ← delete
POST   /sessions/{id}/pipeline/start        ← delete (pipeline still triggered internally by LaunchService)
GET    /sessions/{id}/events                ← delete OR rename to /launches/{id}/jurisdictions/{code}/events
POST   /sessions/{sessionId}/evidence/*     ← keep for now; internal only, not in UI
GET    /sessions/{sessionId}/report.pdf     ← delete (replaced by proof-pack)
```

---

## Parallel tracks — three teammates

Each track is self-contained with file paths, success criteria, and brief enough to hand to a fresh Sonnet subagent. **All three can start simultaneously at hour 0.** Track A publishes the mock JSON fixtures in a shared file at hour 0 so B and C can hit them.

---

### Track A — Backend (Teammate A)
**Scope:** Add `Launch.kind` + GSI + regulator endpoints + proof-tree wrapper + hide sessions. Java Spring Boot 4, AWS SDK v2, DynamoDB Enhanced Client. Base dir: `D:\hackathon\backend\java-backend\src\main\java\com\bunq\javabackend`.

**Tasks (sequential within track, but the whole track is parallel to B and C):**

**A1. Publish mock fixtures (30 min, FIRST — unblocks B and C)**
- Create `D:\hackathon\backend\MOCK_API.md` with sample JSON response for every endpoint in the API contract above (6 countries × 3 sample launches). Commit immediately.

**A2. Add `Launch.kind` field (45 min)**
- Add enum `LaunchKind` in `model/launch/LaunchKind.java`: `PRODUCT, POLICY, PROCESS`.
- Add `kind` field to `Launch.java` (default `PRODUCT`); use a `@DynamoDbConvertedBy` enum converter if needed (pattern already used for `SeverityConverter`).
- Update `CreateLaunchRequestDTO` and `LaunchResponseDTO` / `LaunchSummaryDTO` in `dto/`.
- Update `LaunchService.createLaunch` to accept + persist.
- **DoD:** `POST /launches {"kind":"POLICY", ...}` round-trips correctly; `GET /launches/{id}` returns it.

**A3. Add `jurisdiction-index` GSI on JurisdictionRun table (1h)**
- Terraform: add GSI block to the `launchlens-jurisdiction-runs` table definition (same dir as existing TF).
- `JurisdictionRun.java`: add `@DynamoDbSecondaryPartitionKey(indexNames="jurisdiction-index")` on `jurisdictionCode`, `@DynamoDbSecondarySortKey(indexNames="jurisdiction-index")` on `launchId`.
- `JurisdictionRunRepository.java`: add `List<JurisdictionRun> findByJurisdiction(String code)`.
- **DoD:** unit test (or Postman) query returns all runs for `NL` across launches.

**A4. Regulator endpoints (2h)**
- New `JurisdictionsOverviewController.java`:
  - `GET /jurisdictions` → aggregate via `jurisdictionRunRepository.scanAll()` grouped by code (it's 6 countries, scan is fine); compute `aggregateVerdict` = worst of children.
  - `GET /jurisdictions/{code}/launches` → `findByJurisdiction(code)` + hydrate `Launch.name + kind` (batch get).
- Add worst-verdict helper in a new `service/JurisdictionOverviewService.java`.
- **DoD:** both endpoints return contract-shaped JSON for seeded data.

**A5. Proof-tree wrapper endpoint (45 min)**
- New route on `LaunchController`: `GET /launches/{id}/jurisdictions/{code}/compliance-map`.
- Look up `JurisdictionRun.currentSessionId`, delegate to existing `evidenceController.getComplianceMap(sessionId)` logic (extract to service first if controller-to-controller isn't possible).
- **DoD:** returns nodes+edges JSON matching contract.

**A6. Hide sessions from HTTP (30 min)**
- Delete `SessionController.java` (class + file).
- In `PipelineController`, delete `POST /sessions/{id}/pipeline/start` (pipeline now only kicked off by `LaunchService.addJurisdiction`). Keep the SSE `GET /sessions/{id}/events` for now OR rename it; easier: keep but document it as internal.
- Delete `ReportController` (`GET /sessions/{id}/report.pdf`) — replaced by proof-pack.
- Do NOT delete `SessionService`, `SessionRepository`, `Session` model — still used by pipeline.
- **DoD:** `GET /api/v1/sessions` returns 404; full launch flow still works end-to-end.

**A7. Seed script for demo (1h)**
- Create `D:\hackathon\backend\scripts\seed-demo.sh` (or Java CLI runner) that creates 3 launches across the 6 countries:
  - `Crypto Debit Card` (PRODUCT) → NL, DE, FR, UK, US
  - `ToC §5.3 — Sanctions Screening` (POLICY) → NL, DE, FR, IE
  - `KYC Onboarding Flow` (PROCESS) → NL, DE, UK, US, IE
- Use existing curl / REST calls. Run against staging.
- **DoD:** `GET /launches` returns 3 items; `GET /jurisdictions` returns 6.

**Total Track A: ~6h. Critical path.**

**Files modified:**
- `model/launch/Launch.java`, new `LaunchKind.java`, new `LaunchKindConverter.java`
- `model/launch/JurisdictionRun.java` (GSI annotations)
- `repository/JurisdictionRunRepository.java`
- `controller/LaunchController.java`, new `controller/JurisdictionsOverviewController.java`
- Delete: `controller/SessionController.java`, `controller/ReportController.java`, parts of `controller/PipelineController.java`
- `service/LaunchService.java`, new `service/JurisdictionOverviewService.java`
- `dto/request/CreateLaunchRequestDTO.java`, `dto/response/LaunchSummaryDTO.java`, `dto/response/LaunchResponseDTO.java`
- Terraform file defining `launchlens-jurisdiction-runs` (add GSI)
- New: `scripts/seed-demo.sh`, `MOCK_API.md`

---

### Track B — Frontend Expansion mode (Teammate B)
**Scope:** `/launches` list, `/launches/new` wizard, `/launches/:id` with world-map verdict matrix. Base dir: `D:\hackathon\Bunq\frontend\src`. React 19 + Vite + TS + Tailwind 3. Map via `globe.gl` (3D) and `d3-geo` (2D) — both already installed.

**Tasks:**

**B1. API client + types (1h, FIRST)**
- New `src/api/client.ts`: typed `fetch` wrapper with `VITE_API_BASE` env (default `http://localhost:8080/api/v1`). Export `api.get<T>(path)`, `api.post<T>(path, body)`, `api.download(path)`.
- New `src/api/types.ts`: TS interfaces matching the API contract above (`Launch`, `LaunchKind`, `JurisdictionRun`, `Verdict`, `JurisdictionOverview`, `JurisdictionDetail`, `ComplianceGraph`).
- New `src/api/mock.ts`: reads JSON fixtures from `MOCK_API.md` (Track A commits at hour 0). Toggled by `VITE_USE_MOCK=true`.
- New `src/api/services/launches.ts`: `listLaunches()`, `getLaunch(id)`, `createLaunch(body)`, `addJurisdiction(id, code)`, `downloadProofPack(id, code)`.
- **DoD:** in dev tools, `api.getLaunches()` returns typed data (mock or real).

**B2. Top-bar mode toggle (45 min)**
- New `src/components/ModeToggle.tsx`: two-segment control, stores current mode in `useState` lifted to `App.tsx` OR in `localStorage`. Renders `[🚀 Expansion] [⚖️ Regulator]`.
- Mount in `App.tsx` as persistent header. Clicking navigates to `/launches` or `/jurisdictions`.
- **DoD:** toggle visible on every page, switches route.

**B3. `/launches` list page (1.5h)**
- Replace stub `src/pages/LaunchesPage.tsx`. Card grid: each card = Launch with kind badge (color-coded: PRODUCT=blue, POLICY=purple, PROCESS=amber), name, `jurisdictionCount` chip, `aggregateVerdict` traffic-light pill.
- Empty state: "Create your first launch" → `/launches/new`.
- Load via `api.listLaunches()`.
- **DoD:** seeded 3 launches visible as cards.

**B4. `/launches/new` wizard (1.5h)**
- New `src/pages/LaunchNewPage.tsx`. 3 steps:
  1. Name + Kind (segmented control with 3 options)
  2. Brief (textarea) + License
  3. Target markets (multi-select from fixed list: NL/DE/FR/UK/US/IE)
- Submit: `POST /launches` → then `POST /launches/{id}/jurisdictions/{code}` for each selected market.
- Redirect to `/launches/:id` after submit.
- **DoD:** wizard completes, new launch appears in list with all selected markets in `RUNNING` status.

**B5. `/launches/:id` detail with world map (2h — the demo hero)**
- New `src/pages/LaunchDetailPage.tsx`. Layout:
  - Header: name, kind badge, brief
  - Left 70%: **2D world map** (reuse `d3.geoNaturalEarth1` pattern from `JurisdictionsPage.tsx:129-200` — copy the D3 map rendering, NOT the globe, for this view). Countries in the launch's 6-market set tinted by verdict: GREEN=#22c55e, AMBER=#f59e0b, RED=#ef4444, RUNNING=gray pulse. Others grayscale.
  - Right 30%: when a country is clicked, show drawer with verdict, gapsCount, sanctionsHits, lastRunAt, [⬇ Download Proof Pack], [View compliance graph] link to `/jurisdictions/:code/launches/:id`
- Use `api.getLaunch(id)`; poll every 5s while any status is `RUNNING`.
- **DoD:** clicking NL in a Crypto Card launch shows the NL drawer with real verdict and downloads a real proof-pack ZIP.

**Total Track B: ~7h. Use recommended components from `JurisdictionsPage.tsx` as the pattern reference — do not rewrite the D3 map logic from scratch.**

**Files created/modified:**
- New: `src/api/client.ts`, `src/api/types.ts`, `src/api/mock.ts`, `src/api/services/launches.ts`, `src/api/services/jurisdictions.ts`
- New: `src/components/ModeToggle.tsx`, `src/components/VerdictPill.tsx`, `src/components/KindBadge.tsx`, `src/components/WorldMapD3.tsx` (extract reusable from existing `JurisdictionsPage.tsx`)
- New: `src/pages/LaunchNewPage.tsx`, `src/pages/LaunchDetailPage.tsx`
- Modified: `src/App.tsx` (new routes, mount ModeToggle), `src/pages/LaunchesPage.tsx`

---

### Track C — Frontend Regulator mode + compliance graph (Teammate C)
**Scope:** rewire existing `/jurisdictions` to real data; new `/jurisdictions/:code` feature matrix; wire `GraphPage` to real proof-tree. Base dir: same as Track B.

**Depends on:** `src/api/client.ts`, `src/api/types.ts`, `WorldMapD3.tsx`, `KindBadge.tsx`, `VerdictPill.tsx` from Track B. **Sync point at hour 1.** Track C starts with its own mock service layer and merges when B1 lands.

**Tasks:**

**C1. Refactor `JurisdictionsPage.tsx` to regulator heatmap (2h)**
- Current `JurisdictionsPage.tsx:7-53` has hardcoded `countryStatus` and `countryDetails` — replace with real data from `api.getJurisdictionsOverview()` (`GET /jurisdictions`).
- Map `aggregateVerdict` → color: GREEN/AMBER/RED/inactive (countries with zero runs).
- Keep the globe AND 2D map toggle (it's cool, already works).
- Click country → navigate to `/jurisdictions/:code`.
- Sidebar: replace mock content with country's `launchCount`, `worstVerdict`, and a "Open detail →" button.
- **DoD:** 6 demo countries tinted correctly; clicking NL navigates to `/jurisdictions/NL`.

**C2. `/jurisdictions/:code` feature matrix (2h)**
- New `src/pages/JurisdictionDetailPage.tsx`. Layout:
  - Header: country name + flag emoji + aggregate verdict
  - Main: table with rows = launches in this country. Columns: Kind (badge) · Launch name · Verdict (pill) · Gaps · Sanctions hits · Last run · Actions ([⬇ Proof Pack] [📊 Graph])
  - "📊 Graph" navigates to `/jurisdictions/:code/launches/:id` (compliance map).
  - "⬇ Proof Pack" triggers `api.downloadProofPack(launchId, code)`.
- Source: `GET /jurisdictions/{code}/launches`.
- Risk matrix row at top: aggregate by kind: PRODUCT 🔴 / POLICY 🟢 / PROCESS 🟡.
- **DoD:** on `/jurisdictions/NL`, see 3 seeded launches as rows with correct kind badges and verdicts; clicking proof-pack downloads a ZIP.

**C3. Rewire `GraphPage.tsx` to real proof-tree (2h)**
- Current `GraphPage.tsx:36-87` has `MOCK_NODES`/`MOCK_LINKS` — replace with `api.getComplianceGraph(launchId, code)`.
- Make it a routed page `/jurisdictions/:code/launches/:id` (add route, read params).
- Map node types to existing colors: `obligation`→terms, `control`→licensing, `gap`→aml (red-ish), `evidence`→reports.
- Keep drag/zoom/click interactions (D3 force sim) — they already work.
- Click a gap node → detail panel with severity + recommended action (from compliance-map payload).
- Add header with breadcrumb: `Jurisdiction NL / Crypto Debit Card / Compliance Graph`.
- **DoD:** on `/jurisdictions/NL/launches/{launchId}`, see real obligation→control→gap nodes from the seeded Crypto Card launch.

**C4. Ask page cleanup / polish (30 min, only if time left)**
- Remove or redirect `/ask` — not part of the demo story.
- If keeping, rewire the search bar to call `/rag/query` with jurisdictional filter.

**Total Track C: ~6h.**

**Files created/modified:**
- Modified: `src/pages/JurisdictionsPage.tsx` (real data, click-through)
- New: `src/pages/JurisdictionDetailPage.tsx`
- Modified: `src/pages/GraphPage.tsx` (real data + route params)
- New: `src/api/services/jurisdictions.ts` (if not created by Track B first)
- Modified: `src/App.tsx` (new routes: `/jurisdictions/:code`, `/jurisdictions/:code/launches/:id`)

---

## Coordination notes

- **Hour 0 sync:** Track A publishes `MOCK_API.md` with real JSON shapes. Track B publishes `src/api/types.ts` + `src/api/client.ts`. Track C imports both.
- **Hour 3 sync:** Track A has GSI + regulator endpoints live on staging. B and C flip `VITE_USE_MOCK=false` and hit the real backend.
- **Hour 6 sync:** end-to-end demo rehearsal. Track A runs seed script. B and C click through the full demo path.
- **Shared components** (`ModeToggle`, `VerdictPill`, `KindBadge`, `WorldMapD3`) are owned by Track B; Track C imports them. Track B lands B1 + B2 + extracts `WorldMapD3` by hour 2.

---

## Demo script (90 seconds)

1. **[Expansion mode]** Open `/launches`. Show three launch cards: Crypto Card (PRODUCT), ToC §5.3 (POLICY), KYC Flow (PROCESS). → "Launches aren't just products — they're anything we take to regulators."
2. Click Crypto Card → world map with 5 countries tinted 🟢🟡🔴. → "Here's where we stand across markets."
3. Click NL 🟡 → drawer → **Download Proof Pack**. Open the ZIP: cover.pdf, mappings.xlsx, gaps.pdf, sanctions.pdf, evidence/, audit_trail.json. → "This is what DNB receives."
4. **[Toggle to Regulator mode]** → `/jurisdictions`. World map heatmap. Click NL.
5. `/jurisdictions/NL` — feature matrix. Three rows. → "From NL ops perspective: here's every launch and its compliance state in our country."
6. Click 📊 on Crypto Card → compliance graph. Click a red gap node → "MiCA Art 75 — sanctions screening — needs ToC §5.3 update."
7. Close. → "One domain model, two readings. Ops and Product both have the view they need."

---

## Out of scope (explicitly)

- No Feature catalog entity — `Launch.kind` is enough.
- No session renaming inside backend — only HTTP route deletion.
- No changes to Session/Obligation/Control/Mapping/Gap/Evidence models.
- No new pipeline stages.
- No i18n, no auth, no mobile layout, no dark-mode toggle (Tailwind default stays).
- No real KB ingestion during hackathon — seed script uses existing fixtures or mocked verdicts.
- `/ask` page is not part of the demo; leave as-is or remove.

---

## Verification

**Backend (Track A):**
```
curl -X POST  /api/v1/launches -d '{"name":"Crypto Card","kind":"PRODUCT","brief":"...","license":"EMI"}'
curl -X POST  /api/v1/launches/{id}/jurisdictions/NL
curl          /api/v1/launches/{id}                                  # includes kind + jurisdictionRuns[]
curl          /api/v1/jurisdictions                                  # 6 rows
curl          /api/v1/jurisdictions/NL/launches                      # 3 rows with kind
curl -o pp.zip /api/v1/launches/{id}/jurisdictions/NL/proof-pack     # valid ZIP
curl          /api/v1/sessions                                       # 404 expected
```

**Frontend (Tracks B+C):** run `yarn dev`, click through the full demo script above against the real backend. Record the demo once and attach to the hackathon submission.

**Critical files reference:**
- `D:\hackathon\backend\java-backend\src\main\java\com\bunq\javabackend\model\launch\Launch.java`
- `D:\hackathon\backend\java-backend\src\main\java\com\bunq\javabackend\model\launch\JurisdictionRun.java`
- `D:\hackathon\backend\java-backend\src\main\java\com\bunq\javabackend\controller\LaunchController.java`
- `D:\hackathon\backend\java-backend\src\main\java\com\bunq\javabackend\service\LaunchService.java`
- `D:\hackathon\backend\java-backend\src\main\java\com\bunq\javabackend\service\ProofPackService.java` (already done — uses JurisdictionRun.currentSessionId)
- `D:\hackathon\Bunq\frontend\src\App.tsx`
- `D:\hackathon\Bunq\frontend\src\pages\JurisdictionsPage.tsx` (copy D3 map pattern from here)
- `D:\hackathon\Bunq\frontend\src\pages\GraphPage.tsx` (copy D3 force pattern)
- `D:\hackathon\Bunq\frontend\src\pages\LaunchesPage.tsx` (currently stub)
