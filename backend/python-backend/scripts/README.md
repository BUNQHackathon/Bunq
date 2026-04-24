# LaunchLens Data-Prep Scripts

Scripts for seeding sanctions data and the Bedrock Knowledge Base corpus.
Run these **once on Thursday** before `terraform apply`.

## Prerequisites

```bash
cd python-backend/scripts
pip install -r requirements.txt
```

AWS credentials must be configured (`aws configure --profile launchlens` or env vars).

---

## Run Order

### 1. (Thursday prep) Download + normalize OFAC SDN sanctions list

```bash
python scripts/normalize_sanctions.py --source ofac_sdn --upload-s3 --seed-dynamo
```

- Downloads 3 OFAC CSVs to `scripts/.cache/ofac/` (cached on first run)
- Writes canonical JSONL to `scripts/.cache/ofac_sdn.jsonl`
- Uploads to `s3://launchlens-sanctions/ofac_sdn.jsonl`
- Batch-writes to DynamoDB `launchlens-sanctions-entities` table

**Dry run (no AWS calls):**

```bash
python scripts/normalize_sanctions.py --source ofac_sdn --dry-run
```

**Offline test with fixture CSVs:**

```bash
python scripts/normalize_sanctions.py \
    --source ofac_sdn \
    --input scripts/fixtures/ofac_sample \
    --output-jsonl /tmp/ofac_test.jsonl \
    --dry-run
```

### 2. Download corpus (GDPR + bunq + NIST)

```bash
bash scripts/download_corpus.sh
```

Downloads into `java-backend/seed/{regulations,policies,controls}/`.
Re-runnable — skips files already present. Use `--force` to re-download.

**Corpus downloaded:**
- `regulations/gdpr.pdf` — GDPR full text (Eur-Lex)
- `policies/bunq_tc.pdf` — bunq T&C (personal)
- `policies/bunq_privacy.pdf` — bunq Privacy Policy
- `policies/starling_tc.pdf` — Starling Bank T&C
- `policies/wise_privacy.pdf` — Wise Privacy Policy
- `controls/nist_800_53_rev5.pdf` — NIST SP 800-53 rev5 full PDF
- `controls/nist_800_53_rev5.xlsx` — NIST control catalog XLSX

> **Note on bunq URLs:** bunq does not publish stable direct PDF URLs.
> If `bunq_tc.pdf` fails to download, manually download from
> https://www.bunq.com/terms-and-conditions and place at
> `java-backend/seed/policies/bunq_tc.pdf`.
> The existing Dutch PDFs in `seed/policies/` are already usable.

### 3. Parse NIST controls to JSONL

```bash
python scripts/parse_nist_controls.py \
    --input java-backend/seed/controls/nist_800_53_rev5.pdf \
    --output java-backend/seed/controls/nist_subset.jsonl
```

Extracts AC / IA / SC / AU / SI controls.
Falls back to `scripts/fixtures/nist_subset_fallback.jsonl` if PDF yields <30 rows.

XLSX alternative (preferred if available):

```bash
python scripts/parse_nist_controls.py \
    --input java-backend/seed/controls/nist_800_53_rev5.xlsx \
    --output java-backend/seed/controls/nist_subset.jsonl \
    --limit 50
```

### 4. Apply Terraform — picks up seed/ files, triggers KB ingestion

```bash
cd java-backend/infra && terraform apply
```

Terraform reads all files under `java-backend/seed/{regulations,policies,controls}/`,
uploads them to the `launchlens-kb-*` S3 buckets, and triggers Bedrock KB ingestion.

---

## Running Tests

```bash
pytest python-backend/scripts/tests/ -q
```

All tests are offline — no network or AWS calls required.

---

## DynamoDB Table Layout

Table: `launchlens-sanctions-entities`

| Attribute | Type | Notes |
|---|---|---|
| `id` | String (PK) | `{list_source}#{list_entry_id}` e.g. `OFAC_SDN#sdn-12345` |
| `list_source` | String | `OFAC_SDN`, `OFAC_CONS`, `EU_CONS`, `UN`, `UK` |
| `entity_name` | String | Original name |
| `entity_name_normalized` | String | Lowercase, no punctuation, no legal suffixes |
| `aliases` | List[String] | Alt names from alt.csv |
| `country` | String | 2-letter ISO code |
| `type` | String | `individual\|company\|organization\|government\|unknown` |
| `list_entry_id` | String | Source-specific ID |
| `list_version_timestamp` | String | ISO8601 UTC |

Sidecar queries via `scan` + `FilterExpression` on `entity_name_normalized`.

---

## S3 Bucket Names

| Bucket | Purpose |
|---|---|
| `launchlens-sanctions` | Canonical sanctions JSONL (one file per source) |
| `launchlens-kb-regulations` | Regulation PDFs → Bedrock KB |
| `launchlens-kb-policies` | Policy PDFs → Bedrock KB |
| `launchlens-kb-controls` | Controls JSONL/PDF → Bedrock KB |

> S3_SANCTIONS_BUCKET env var overrides the sanctions bucket name if you
> used a non-default `project_prefix` in Terraform.
