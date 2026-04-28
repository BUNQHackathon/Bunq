# MOCK_API.md — Track A reference

Base: `http://localhost:8080/api/v1` (configurable via `BASE_URL`).

All JSON samples are illustrative — field types match the real DTOs but values are fabricated for frontend stubbing. See `API.md` for the canonical surface and `BACKEND.md` for architecture.

---

## 1. POST /launches — Bulk create

```bash
curl -sS -X POST http://localhost:8080/api/v1/launches \
  -H 'content-type: application/json' \
  -d '{
    "name": "Crypto Debit Card",
    "kind": "PRODUCT",
    "brief": "EU debit card backed by USDC with instant spend and per-transaction sanctions screening",
    "license": "EMI",
    "jurisdictions": ["NL","DE","FR","UK","US"]
  }'
```

**Response `201 Created`:**
```json
[
  {
    "id": "01JK4ZR8ABCDEF001",
    "jurisdictionCode": "NL",
    "status": "PENDING"
  },
  {
    "id": "01JK4ZR8ABCDEF002",
    "jurisdictionCode": "DE",
    "status": "PENDING"
  },
  {
    "id": "01JK4ZR8ABCDEF003",
    "jurisdictionCode": "FR",
    "status": "PENDING"
  },
  {
    "id": "01JK4ZR8ABCDEF004",
    "jurisdictionCode": "UK",
    "status": "PENDING"
  },
  {
    "id": "01JK4ZR8ABCDEF005",
    "jurisdictionCode": "US",
    "status": "PENDING"
  }
]
```

---

## 2. GET /launches — List

```bash
curl -sS http://localhost:8080/api/v1/launches
```

**Response `200 OK`:**
```json
[
  {
    "id": "01JK4ZR8ABCDEF001",
    "name": "Crypto Debit Card",
    "kind": "PRODUCT",
    "license": "EMI",
    "jurisdictions": ["NL","DE","FR","UK","US"],
    "aggregateVerdict": "REQUIRES_CHANGES",
    "createdAt": "2026-04-24T10:00:00Z",
    "updatedAt": "2026-04-24T10:05:32Z"
  },
  {
    "id": "01JK4ZR8ABCDEF010",
    "name": "ToC Section 5.3 Sanctions Screening",
    "kind": "POLICY",
    "license": "EMI",
    "jurisdictions": ["NL","DE","FR","IE"],
    "aggregateVerdict": "CLEAR",
    "createdAt": "2026-04-24T10:01:00Z",
    "updatedAt": "2026-04-24T10:06:10Z"
  },
  {
    "id": "01JK4ZR8ABCDEF020",
    "name": "KYC Onboarding Flow",
    "kind": "PROCESS",
    "license": "EMI",
    "jurisdictions": ["NL","DE","UK","US","IE"],
    "aggregateVerdict": "BLOCKED",
    "createdAt": "2026-04-24T10:02:00Z",
    "updatedAt": "2026-04-24T10:07:45Z"
  }
]
```

---

## 3. GET /launches/{id} — Detail

```bash
curl -sS http://localhost:8080/api/v1/launches/01JK4ZR8ABCDEF001
```

**Response `200 OK`:**
```json
{
  "id": "01JK4ZR8ABCDEF001",
  "name": "Crypto Debit Card",
  "kind": "PRODUCT",
  "brief": "EU debit card backed by USDC with instant spend and per-transaction sanctions screening",
  "license": "EMI",
  "aggregateVerdict": "REQUIRES_CHANGES",
  "createdAt": "2026-04-24T10:00:00Z",
  "jurisdictionRuns": [
    {
      "jurisdictionCode": "NL",
      "verdict": "REQUIRES_CHANGES",
      "summary": "MiCA licensing pathway available; sanctions screening module must be certified under DNB supervisory expectations.",
      "requiredChanges": [
        "Integrate DNB-approved OFAC/EU list vendor",
        "Update privacy notice for automated transaction decisions (AVG Art. 22)"
      ],
      "blockers": [],
      "proofPackAvailable": true,
      "runAt": "2026-04-24T10:05:32Z"
    },
    {
      "jurisdictionCode": "DE",
      "verdict": "REQUIRES_CHANGES",
      "summary": "BaFin requires explicit crypto asset service provider registration under MiCAR.",
      "requiredChanges": [
        "File CASP registration with BaFin",
        "Add German-language T&C annex"
      ],
      "blockers": [],
      "proofPackAvailable": true,
      "runAt": "2026-04-24T10:05:40Z"
    },
    {
      "jurisdictionCode": "US",
      "verdict": "BLOCKED",
      "summary": "State-by-state money transmitter licensing required; NYDFS BitLicense outstanding.",
      "requiredChanges": [],
      "blockers": [
        "NYDFS BitLicense not held — cannot serve NY residents",
        "FinCEN MSB registration must be renewed before go-live"
      ],
      "proofPackAvailable": false,
      "runAt": "2026-04-24T10:05:55Z"
    }
  ]
}
```

---

## 4. POST /launches/{id}/jurisdictions/{code}/run — Re-run

```bash
curl -sS -X POST \
  http://localhost:8080/api/v1/launches/01JK4ZR8ABCDEF001/jurisdictions/NL/run
```

**Response `202 Accepted`:**
```json
{
  "sessionId": "01JK4ZR8SES0042",
  "jurisdictionCode": "NL",
  "status": "RUNNING",
  "startedAt": "2026-04-24T11:00:00Z"
}
```

---

## 5. GET /launches/{id}/jurisdictions/{code}/proof-pack — ZIP download

```bash
curl -sS -OJ \
  http://localhost:8080/api/v1/launches/01JK4ZR8ABCDEF001/jurisdictions/NL/proof-pack
```

**Response `200 OK`** — `Content-Type: application/zip`

Body is a binary ZIP archive. On missing proof pack:

```json
{
  "status": 404,
  "error": "Proof pack not yet available for launch 01JK4ZR8ABCDEF001 / NL"
}
```

---

## 6. GET /launches/{id}/jurisdictions/{code}/compliance-map — Graph

```bash
curl -sS \
  http://localhost:8080/api/v1/launches/01JK4ZR8ABCDEF001/jurisdictions/NL/compliance-map
```

**Response `200 OK`:**
```json
{
  "nodes": [
    {
      "id": "OBL-mica-75",
      "type": "obligation",
      "label": "MiCA Art 75 Counterparty Screening",
      "metadata": { "jurisdiction": "NL", "regulation": "MiCA" }
    },
    {
      "id": "CTL-sanctions-svc",
      "type": "control",
      "label": "Sanctions Screening Service",
      "metadata": { "owner": "Compliance Eng", "status": "implemented" }
    },
    {
      "id": "GAP-ofac-missing",
      "type": "gap",
      "label": "OFAC list not integrated",
      "metadata": { "severity": 0.75, "residualRisk": 0.7 }
    }
  ],
  "edges": [
    {
      "source": "OBL-mica-75",
      "target": "CTL-sanctions-svc",
      "type": "maps_to",
      "confidence": 0.82
    },
    {
      "source": "OBL-mica-75",
      "target": "GAP-ofac-missing",
      "type": "has_gap"
    }
  ]
}
```

---

## 7. GET /jurisdictions — Overview array

```bash
curl -sS http://localhost:8080/api/v1/jurisdictions
```

**Response `200 OK`:**
```json
[
  {
    "code": "NL",
    "name": "Netherlands",
    "region": "EU",
    "regulators": ["DNB","AFM"],
    "riskScore": 0.22,
    "supportedLicenses": ["EMI","CASP","PSP"]
  },
  {
    "code": "DE",
    "name": "Germany",
    "region": "EU",
    "regulators": ["BaFin"],
    "riskScore": 0.28,
    "supportedLicenses": ["EMI","CASP","PSP"]
  },
  {
    "code": "US",
    "name": "United States",
    "region": "APAC",
    "regulators": ["FinCEN","NYDFS","OCC"],
    "riskScore": 0.61,
    "supportedLicenses": ["MSB","NYDFS-BitLicense"]
  }
]
```

---

## 8. GET /jurisdictions/{code}/triage — Three-bucket object

```bash
curl -sS http://localhost:8080/api/v1/jurisdictions/NL/triage
```

**Response `200 OK`:**
```json
{
  "jurisdictionCode": "NL",
  "green": [
    {
      "id": "OBL-mica-50",
      "label": "MiCA Art 50 White-paper Disclosure",
      "status": "COVERED",
      "control": "CTL-whitepaper-gen"
    }
  ],
  "amber": [
    {
      "id": "OBL-dnb-sanctions",
      "label": "DNB Sanctions List Screening",
      "status": "PARTIAL",
      "gap": "OFAC list missing from vendor feed",
      "suggestedAction": "Expand screening vendor to include OFAC universe"
    }
  ],
  "red": [
    {
      "id": "OBL-avg-22",
      "label": "AVG Art. 22 Automated Decision Notice",
      "status": "MISSING",
      "gap": "No automated-decision disclosure in current privacy notice",
      "suggestedAction": "Add Art. 22 section to privacy notice before go-live"
    }
  ]
}
```

---

## 9. GET /jurisdictions/catalog — Static catalog metadata

```bash
curl -sS http://localhost:8080/api/v1/jurisdictions/catalog
```

**Response `200 OK`:**
```json
{
  "version": "2026-Q2",
  "lastUpdated": "2026-04-01T00:00:00Z",
  "jurisdictions": [
    {
      "code": "NL",
      "name": "Netherlands",
      "region": "EU",
      "frameworks": ["MiCA","GDPR","AML6D","DNB-CDD"],
      "entryPoints": ["EMI","CASP"]
    },
    {
      "code": "DE",
      "name": "Germany",
      "region": "EU",
      "frameworks": ["MiCA","GDPR","AML6D","KWG"],
      "entryPoints": ["EMI","CASP"]
    },
    {
      "code": "FR",
      "name": "France",
      "region": "EU",
      "frameworks": ["MiCA","GDPR","AML6D","PSAN"],
      "entryPoints": ["EMI","PSAN"]
    },
    {
      "code": "IE",
      "name": "Ireland",
      "region": "EU",
      "frameworks": ["MiCA","GDPR","AML6D","CBI-AML"],
      "entryPoints": ["EMI","CASP"]
    },
    {
      "code": "UK",
      "name": "United Kingdom",
      "region": "UK",
      "frameworks": ["FCA-CASP","UK-GDPR","MLR2017"],
      "entryPoints": ["EMI","FCA-Crypto"]
    },
    {
      "code": "US",
      "name": "United States",
      "region": "US",
      "frameworks": ["BSA","OFAC","FinCEN-MSB","NYDFS-23NYCRR200"],
      "entryPoints": ["MSB","NYDFS-BitLicense"]
    }
  ]
}
```

---

## 10. POST /chat/with-graph — SSE stream

```bash
curl -sS -N -X POST http://localhost:8080/api/v1/chat/with-graph \
  -H 'content-type: application/json' \
  -H 'accept: text/event-stream' \
  -d '{
    "launchId": "01JK4ZR8ABCDEF001",
    "jurisdictionCode": "NL",
    "message": "Does MiCA Art 75 require us to screen counterparties in real-time?"
  }'
```

**Response `200 OK`** — `Content-Type: text/event-stream`

```
event: graph_node
data: {"id":"OBL-mica-75","type":"obligation","label":"MiCA Art 75 Counterparty Screening","metadata":{"jurisdiction":"EU","regulation":"MiCA"}}

event: graph_node
data: {"id":"CTL-sanctions-svc","type":"control","label":"Sanctions Screening Service","metadata":{"owner":"Compliance Eng"}}

event: graph_edge
data: {"source":"OBL-mica-75","target":"CTL-sanctions-svc","type":"maps_to","confidence":0.82}

event: graph_node
data: {"id":"GAP-ofac-missing","type":"gap","label":"OFAC list not integrated","metadata":{"severity":0.75,"residualRisk":0.7}}

event: graph_edge
data: {"source":"OBL-mica-75","target":"GAP-ofac-missing","type":"has_gap"}

event: chat_delta
data: {"text":"Yes, with caveats. MiCA Article 75 requires..."}

event: chat_delta
data: {"text":" real-time screening of all outbound counterparties against EU, UN, and OFAC lists. Your current control partially satisfies this but the OFAC feed gap must be closed before go-live."}

event: done
data: {"chatId":"chat_01HXX","finalGraph":{"nodes":[...],"edges":[...]}}
```

---

## Deleted (session hide)

- POST /sessions
- GET /sessions
- GET /sessions/{id}
- POST /sessions/{id}/pipeline/start
- GET /sessions/{id}/report.pdf

Kept internally: GET /sessions/{id}/events (SSE, used by launch-detail polling) and GET /sessions/{id}/compliance-map (consumed by the new /launches/{id}/jurisdictions/{code}/compliance-map wrapper).
