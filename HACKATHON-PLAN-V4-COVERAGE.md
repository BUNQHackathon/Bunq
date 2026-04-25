# LaunchLens v4 — Coverage Architecture (delta on v3)

## Context

V3 is shipping. The pipeline works end-to-end: Launch → JurisdictionRun → Session → obligations/controls/mappings/gaps. **One thing in v3 is wrong for the demo narrative**: `AutoDocService.forJurisdiction(code)` selects top-10 documents by filename keyword match. That gives lottery results — NL Launch might pull random DNB leaflets and miss MiCA. It also kills the pitch ("we cover all applicable regulations" — no, we sample 10).

**This plan flips that to coverage-first**: every Document carries an explicit `jurisdictions` set, the AutoDocService returns *every* applicable regulation (no limit), and a pre-filter keeps LLM cost bounded. After this change, "NL = MiCA + DORA + GDPR + PSD2 + DNB Wwft + 4 EU directives, 100% covered" is true at runtime.

This plan is a **delta on v3** — everything else in v3 stays. Estimated ~5h backend + 1h seed.

---

## Decisions locked

| # | Decision |
|---|---|
| 1 | `Document.jurisdictions: Set<String>` — explicit at upload, never inferred from filename. ISO-2 codes (`NL`, `DE`, `FR`, `UK`, `US`, `IE`, plus `EU` as expansion key). |
| 2 | New denormalized table `launchlens-doc-jurisdictions` (PK `jurisdiction`, SK `document_id`). Fan-out write on Document save. Why: DynamoDB can't index a StringSet directly; this is the standard workaround for "give me all docs for jurisdiction X". |
| 3 | `AutoDocService.forJurisdiction(code)` rewritten to query the new table. **No `limit` arg.** Returns *every* matching Document. EU expansion: when querying `NL`, also pull docs tagged `EU`. |
| 4 | Pre-filter before LLM-mapping: for each obligation, KB-retrieve top-20 candidate controls by cosine similarity *before* sending pairs to Bedrock. Bounds fan-out at `obligations × 20`, not `obligations × controls`. |
| 5 | Curated corpus of **40 PDFs** (14 external regulations + 10 internal bunq-style + 16 supporting national/guidance). Driven by a static `seed/regulations.yaml` mapping regulation → applicable jurisdictions. Replaces the "1060-doc dump" approach. |
| 6 | `JurisdictionInference.inferFromFilename` is **only** kept for legacy paths (KB-retrieve hint in chat-with-graph). Removed from the upload + AutoDocService paths. |

---

## Data model — additive

### Edit `Document.java`
- Add `private Set<String> jurisdictions;` with `@DynamoDbAttribute("jurisdictions")` (StringSet in DynamoDB).
- Default: empty set (server fills `["EU"]` if request omits it for regulation kind).

### New table `launchlens-doc-jurisdictions`
```
PK: jurisdiction        (String, e.g. "NL")
SK: document_id         (String, SHA-256)
attrs: kind, filename, last_used_at
```
Terraform: `infra/dynamodb.tf` — add resource block. Keep projection-type `ALL` (cheap; 40 docs total).

### Edit `CreateDocumentRequest` (or whatever finalize takes)
- Add `Set<String> jurisdictions` (required for `regulation` kind, optional otherwise).

### Edit `Document` finalize flow (`DocumentService.finalize`)
- Persist Document as today, then **fan-out**: for each jurisdiction in the set, `docJurisdictionRepository.put(jurisdiction, documentId, ...)`. Use `BatchWriteItem`.
- On dedupe-touch path: only update `last_used_at` on the existing fan-out rows (don't re-write).

---

## Backend tasks (Track A delta)

### A-COV-1. Document.jurisdictions field + DTO (45 min)
**Files:** `model/document/Document.java`, `dto/request/DocumentFinalizeRequest.java` (or current name), `helper/mapper/DocumentMapper.java`.
- Add field, getter/setter, DynamoDB annotation.
- Update finalize DTO to accept `jurisdictions: ["NL","DE","FR","IE"]`.
- Update mapper.
- **DoD:** `POST /documents/finalize` with `jurisdictions:["NL"]` round-trips; `GET /documents/{id}` returns the field.

### A-COV-2. New denormalized table + repository (1.5h)
**Files:** `infra/dynamodb.tf` (Terraform), `model/document/DocJurisdictionItem.java` (new), `repository/DocJurisdictionRepository.java` (new), `config/DynamoDbConfig.java` (register bean), `application.yaml` (table name).
- Terraform table block (PK `jurisdiction`, SK `document_id`).
- Repository methods:
  - `void putAll(String documentId, Set<String> jurisdictions, Document doc)` — BatchWriteItem fan-out
  - `List<DocJurisdictionItem> findByJurisdiction(String code)` — Query
  - `void deleteAll(String documentId, Set<String> jurisdictions)` — for re-tagging
- **DoD:** unit test (or Postman) — finalize doc with `["NL","DE"]` → query `NL` and `DE` each return one row.

### A-COV-3. Hook fan-out into DocumentService.finalize (30 min)
**File:** `service/DocumentService.java`.
- After `documentRepository.save(doc)`, call `docJurisdictionRepository.putAll(doc.getId(), doc.getJurisdictions(), doc)`.
- On dedup branch: skip the put (rows already exist for the existing doc).
- **DoD:** finalizing a doc with two jurisdictions creates two rows in the new table.

### A-COV-4. Rewrite AutoDocService (45 min)
**File:** `service/AutoDocService.java`.
- New method body: `forJurisdiction(String code)`:
  1. `docJurisdictionRepository.findByJurisdiction(code)` → list of `documentId`
  2. If `code` ≠ `EU`, also `findByJurisdiction("EU")` and merge (EU-wide regulations apply to all member states in our demo set: NL, DE, FR, IE — NOT UK or US).
  3. `documentRepository.batchGet(documentIds)` → hydrated Documents.
  4. **Return all** (no slicing, no `limit`).
- Remove the filename-keyword path entirely.
- Keep `JurisdictionInference.inferFromFilename` in the codebase but not on this call path.
- **DoD:** Launch on NL pulls 9 docs (5 NL-tagged + 4 EU-tagged), Launch on US pulls 5 docs (no EU expansion).

### A-COV-5. Pre-filter before LLM-mapping (1h)
**File:** `service/pipeline/stage/MapObligationsControlsStage.java` (and the extracted `ObligationControlMatcher` if Step 5 from v3 already split it).
- Before the existing semanticMatch loop, for each obligation:
  - Compute embedding (use `BedrockEmbedService` or `KnowledgeBaseService.embed(text)` — whichever pattern exists).
  - `KnowledgeBaseService.retrieveControls(obligation.action, topK=20, filter=sessionControls)` — restrict to the controls in the session, not the global KB.
  - Pass only those 20 candidates to the LLM matcher (instead of all session controls).
- If embeddings layer doesn't exist as a service yet, fall back to: dot-product on KB-returned vectors, or a simple TF-IDF over text. Hackathon-grade is fine.
- **DoD:** mapping stage on a session with 250 obligations × 30 controls completes in <2 min total LLM time (was: 7500 LLM calls; now: 5000).

### A-COV-6. Static regulations catalog + seed script (1h)
**Files:** `seed/regulations.yaml` (new), `scripts/seed-regulations.sh` (new), or extend `seed-demo.sh`.

`seed/regulations.yaml`:
```yaml
# External regulations (14)
regulations:
  - id: mica
    filename: MiCA-Regulation-2023-1114.pdf
    title: "Markets in Crypto-Assets Regulation"
    jurisdictions: [EU]   # expands to NL, DE, FR, IE in AutoDocService
    kind: regulation
  - id: dora
    filename: DORA-Regulation-2022-2554.pdf
    title: "Digital Operational Resilience Act"
    jurisdictions: [EU]
    kind: regulation
  - id: gdpr
    filename: GDPR-Regulation-2016-679.pdf
    title: "General Data Protection Regulation"
    jurisdictions: [EU, UK]   # UK GDPR retained post-Brexit
    kind: regulation
  - id: psd2
    filename: PSD2-Directive-2015-2366.pdf
    title: "Payment Services Directive 2"
    jurisdictions: [EU]
    kind: regulation
  - id: dnb-wwft
    filename: DNB-Wwft-Implementation-2024.pdf
    title: "Wet ter voorkoming van witwassen en financieren van terrorisme"
    jurisdictions: [NL]
    kind: regulation
  - id: bafin-gwg
    filename: BaFin-GwG-Section10.pdf
    title: "German Money Laundering Act §10"
    jurisdictions: [DE]
    kind: regulation
  - id: acpr-cmf-l561
    filename: ACPR-CMF-L561-2.pdf
    title: "Code Monétaire et Financier L561-2"
    jurisdictions: [FR]
    kind: regulation
  - id: fca-emi
    filename: FCA-EMI-Approach-Document.pdf
    title: "FCA Approach to Electronic Money Issuers"
    jurisdictions: [UK]
    kind: regulation
  - id: fca-crypto
    filename: FCA-Crypto-Asset-Guidance.pdf
    title: "FCA Cryptoasset Guidance"
    jurisdictions: [UK]
    kind: regulation
  - id: uk-mlr-2017
    filename: UK-MLR-2017.pdf
    title: "UK Money Laundering Regulations 2017"
    jurisdictions: [UK]
    kind: regulation
  - id: bsa-fincen
    filename: FinCEN-BSA-Guidance.pdf
    title: "Bank Secrecy Act / FinCEN Guidance"
    jurisdictions: [US]
    kind: regulation
  - id: occ-1170
    filename: OCC-Interpretive-Letter-1170.pdf
    title: "OCC Interpretive Letter 1170"
    jurisdictions: [US]
    kind: regulation
  - id: nydfs-bitlicense
    filename: NYDFS-BitLicense-Part200.pdf
    title: "NYDFS BitLicense (23 NYCRR Part 200)"
    jurisdictions: [US]
    kind: regulation
  - id: cbi-fintech
    filename: CBI-Fintech-Authorisation-Guidance.pdf
    title: "Central Bank of Ireland Fintech Authorisation Guidance"
    jurisdictions: [IE]
    kind: regulation
  - id: ie-cja-2010
    filename: Irish-CJA-2010-AML.pdf
    title: "Criminal Justice (Money Laundering and Terrorist Financing) Act 2010"
    jurisdictions: [IE]
    kind: regulation

# Internal bunq policies (5) — apply to all 6 jurisdictions
policies:
  - id: bunq-toc-5-3
    filename: bunq-ToC-Section-5-3-Sanctions.pdf
    title: "bunq Terms & Conditions §5.3 — Sanctions Screening"
    jurisdictions: [NL, DE, FR, UK, US, IE]
    kind: policy
  - id: bunq-privacy
    filename: bunq-Privacy-Policy.pdf
    jurisdictions: [NL, DE, FR, UK, US, IE]
    kind: policy
  - id: bunq-kyc-sop
    filename: bunq-KYC-SOP.pdf
    jurisdictions: [NL, DE, FR, UK, US, IE]
    kind: policy
  - id: bunq-cdd
    filename: bunq-CDD-Procedure.pdf
    jurisdictions: [NL, DE, FR, UK, US, IE]
    kind: policy
  - id: bunq-tx-monitoring
    filename: bunq-Transaction-Monitoring-Policy.pdf
    jurisdictions: [NL, DE, FR, UK, US, IE]
    kind: policy

# Internal controls (5)
controls:
  - id: ctrl-sanctions-screening
    filename: bunq-Control-Sanctions-Screening-v2.pdf
    jurisdictions: [NL, DE, FR, UK, US, IE]
    kind: control
  - id: ctrl-aml-cft
    filename: bunq-Control-AML-CFT-Framework.pdf
    jurisdictions: [NL, DE, FR, UK, US, IE]
    kind: control
  - id: ctrl-kyc-onboarding
    filename: bunq-Control-KYC-Onboarding.pdf
    jurisdictions: [NL, DE, FR, UK, US, IE]
    kind: control
  - id: ctrl-data-retention
    filename: bunq-Control-Data-Retention.pdf
    jurisdictions: [NL, DE, FR, UK, US, IE]
    kind: control
  - id: ctrl-incident-response
    filename: bunq-Control-Incident-Response.pdf
    jurisdictions: [NL, DE, FR, UK, US, IE]
    kind: control

# Supporting / national guidance (16) — added later if time permits
supporting:
  # MiCA national guidance: AFM (NL), BaFin (DE), AMF (FR), CBI (IE) — 4 docs
  # DORA national guidance: 4 docs
  # AML guidance: FATF, Wolfsberg, EBA — 3 docs
  # Crypto guidance: ESMA, ECB, BIS — 3 docs
  # PSD2/payments: EBA RTS — 2 docs
```

**Coverage matrix (auto-derived from yaml):**
- **NL**: MiCA + DORA + GDPR + PSD2 + DNB Wwft + 5 internal = **9 regulations**
- **DE**: MiCA + DORA + GDPR + PSD2 + BaFin GwG + 5 internal = **9 regulations**
- **FR**: MiCA + DORA + GDPR + PSD2 + ACPR + 5 internal = **9 regulations**
- **UK**: GDPR (UK) + FCA EMI + FCA Crypto + UK MLR + 5 internal = **9 regulations**
- **US**: BSA/FinCEN + OCC + NYDFS + 5 internal = **8 regulations**
- **IE**: MiCA + DORA + GDPR + PSD2 + CBI + Irish CJA + 5 internal = **10 regulations**

`scripts/seed-regulations.sh` (bash):
1. Parses yaml (use `yq` or hand-roll grep — yaml is flat).
2. For each entry: `aws s3 cp seed/pdfs/{filename} s3://launchlens-uploads/regulations/{filename}` (or `policies/`, `controls/` by kind).
3. Computes SHA-256 (or relies on S3 Additional Checksums via `--checksum-algorithm SHA256`).
4. `curl POST /api/v1/documents/finalize` with `{filename, contentType, kind, jurisdictions, sha256}`.

**DoD:** running the script populates 24 docs (or 40 with supporting) in DynamoDB and 24 fan-out rows × jurisdictions count. `GET /api/v1/jurisdictions/NL/launches/{id}/applicable-docs` (debug endpoint, optional) returns 9 entries.

### A-COV-7. (Optional, polish) Coverage badge in JurisdictionRun response (30 min)
**File:** `service/LaunchService.java`, `dto/response/JurisdictionRunResponseDTO.java`.
- Add `regulationsCovered: Integer` field on `JurisdictionRun` response — count of distinct Document IDs attached to the session for that run.
- UI shows "9 of 9 regulations checked" in the Launches drawer.
- **DoD:** drawer displays count. Strong demo moment.

---

## Sequencing

Within Track A:
1. A-COV-1 (Document.jurisdictions field) — must land first; everything else depends on the schema.
2. A-COV-2 (denormalized table) — Terraform apply + repository.
3. A-COV-3 (fan-out hook) — wire finalize.
4. A-COV-6 (seed yaml + script + run) — populates data; can run as soon as A-COV-3 is live.
5. A-COV-4 (rewrite AutoDocService) — depends on A-COV-2 and seeded data.
6. A-COV-5 (pre-filter) — depends on A-COV-4 (otherwise mapping is too slow with full coverage).
7. A-COV-7 (coverage badge, optional) — last.

Tracks B and C: **no changes**. They consume the same `GET /launches/{id}` response. The only visible difference is `jurisdictionRuns[].regulationsCovered` field (if A-COV-7 ships) — display it as a small chip.

---

## Verification

```
BASE=http://localhost:8080/api/v1

# Seed: corpus uploaded
bash backend/scripts/seed-regulations.sh
curl -s $BASE/documents | jq 'length'                     # ≥ 24

# Per-jurisdiction document set
curl -s $BASE/jurisdictions/NL/applicable-docs | jq 'length'   # 9
curl -s $BASE/jurisdictions/US/applicable-docs | jq 'length'   # 8
curl -s $BASE/jurisdictions/IE/applicable-docs | jq 'length'   # 10

# Launch on NL pulls all 9, not 10 random
curl -s -X POST $BASE/launches \
  -d '{"name":"Crypto Card","kind":"PRODUCT","brief":"...","license":"EMI","jurisdictions":["NL"]}' \
  | tee /tmp/launch.json
LAUNCH=$(jq -r '.id' /tmp/launch.json)
sleep 60
curl -s $BASE/launches/$LAUNCH | jq '.jurisdictionRuns[0].regulationsCovered'   # 9

# Pre-filter keeps mapping under budget
# Watch logs: "MapObligationsControlsStage: 250 obligations × 20 candidates = 5000 pairs"
# Stage completes in <120s
```

---

## Out of scope for this delta

- Real PDF acquisition (legal text). Use placeholder PDFs with realistic titles for the demo if real text isn't sourced — Textract will pull whatever text exists, mapping is more interesting than literal accuracy.
- The full 16-doc supporting tier — add only if time permits after A-COV-1..6.
- Document re-tagging (changing jurisdictions after upload) — out of scope.
- Embedding cache. Recompute per session is fine for 6-jurisdiction demo.

---

## Critical files

- `model/document/Document.java` — `+jurisdictions` field
- `model/document/DocJurisdictionItem.java` (new)
- `repository/DocJurisdictionRepository.java` (new)
- `config/DynamoDbConfig.java` — register table bean
- `application.yaml` — `aws.dynamodb.doc-jurisdictions-table`
- `infra/dynamodb.tf` — new table + 1 attribute declaration
- `service/DocumentService.java` — fan-out on finalize
- `service/AutoDocService.java` — rewritten query path
- `service/pipeline/stage/MapObligationsControlsStage.java` — pre-filter
- `service/LaunchService.java` — populate `regulationsCovered` (optional)
- `dto/request/DocumentFinalizeRequest.java` — `+jurisdictions`
- `dto/response/JurisdictionRunResponseDTO.java` — `+regulationsCovered` (optional)
- New `seed/regulations.yaml`
- New `scripts/seed-regulations.sh`
