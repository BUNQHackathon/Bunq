# Python sidecar — deployment brief

Handoff brief for the Terraform agent. Audit-dated 2026-04-23 against commit `38649443` + Track B Java changes.

## TL;DR

The sidecar is 95% deployable. Five items to address:

1. **Blocking (already fixed in Track B):** two env-var references in `infra/ecs_*.tf` pointed at `aws_dynamodb_table.this["audit-log"]` which no longer exists after audit-log was promoted to a standalone resource. Now fixed in `ecs_express.tf:101` and `ecs_sidecar.tf:240`.
2. **Blocking (fixed here):** Java's `application.yaml` `sidecar.base-url` is now `${SIDECAR_BASE_URL:http://localhost:8001}` (explicit env override) — was a hardcoded literal that would have ignored the ECS-injected value under edge Spring binding behavior.
3. **Image-build fragility:** the `null_resource.sidecar_image_build` provisioner in `ecs_sidecar.tf:148-180` relies on Docker + buildx on the Terraform runner. Add a standalone script so non-Terraform flows can build/push.
4. **Manual steps** (Terraform can't do these): populate `opensanctions_api_key` at apply time, seed the sanctions Dynamo table post-deploy.
5. **IAM over-grant** (non-blocking): sidecar task role has DynamoDB write perms on `audit-log` and `evidence` that Python doesn't use.

---

## A. What already exists

### Python app (`python-backend/`)
- **Framework:** FastAPI 0.115, uvicorn[standard], Python 3.12.
- **Routers:** `health` (GET `/health`), `sanctions` (POST `/sanctions/screen`), `evidence` (POST `/evidence/hash`), `proof_tree` (GET `/proof-tree/{id}`, GET `/compliance-map/{id}`).
- **Services:** `sanctions_screener.py` (OpenSanctions API + local Dynamo fuzzy match via rapidfuzz/JaroWinkler), `dag_builder.py` (Dynamo reads across obligations/controls/mappings/gaps/evidence).
- **No Bedrock usage anywhere in Python.** No `boto3.client("bedrock-*")` calls.
- **Config:** pydantic-settings in `app/config.py`. Auto-maps field names to upper-case env vars.
- **Dockerfile:** exposes 8001, entrypoint `uvicorn app.main:app --host 0.0.0.0 --port 8001`. Correct.
- **Scripts:** `scripts/normalize_sanctions.py` seeds `launchlens-sanctions-entities` (only `ofac_sdn` source implemented).

### ECR repo
`ecs_sidecar.tf:6-14` — `aws_ecr_repository.sidecar`, name `${local.name_prefix}-sidecar`.

### ECS service
`ecs_sidecar.tf:183-263` — `aws_ecs_express_gateway_service` on the shared `default` cluster. Container port 8001, health check `/health`, CPU 512, memory 1024 MiB, public subnets, no SG (ingress auth is the bearer token only). CloudWatch log group `/ecs/${local.name_prefix}-sidecar` with 7-day retention.

### Secrets (`secrets.tf`)
- `aws_secretsmanager_secret.sidecar_token` — auto-populated with `random_password.sidecar_token` (32 chars, no specials). Same ARN is wired into **both** the sidecar task def and the Java task def, guaranteeing token match.
- `aws_secretsmanager_secret.opensanctions` — populated with `coalesce(var.opensanctions_api_key, "placeholder-update-via-aws-cli")`. Placeholder unless the var is passed.

### IAM (sidecar task role, `ecs_sidecar.tf:17-137`)
Four inline policies:
- `DynamoReadAll` — read all project tables.
- `DynamoWriteSanctionsAuditEvidence` — Put/Update/Delete/BatchWrite on `sanctions-hits`, `audit-log`, `evidence`. **Python only writes to `sanctions-hits`**; the other two are unused (see §D).
- `S3ReadSanctionsAndUploads` — GetObject/ListBucket on uploads bucket, two prefixes.
- `sidecar-secrets` — GetSecretValue on both secrets.
- `sidecar-logs` — CreateLogStream/PutLogEvents on the sidecar log group.

### Env var wiring (Python reads → Terraform injects)

| Python config field | Env var | Injected? |
|---|---|---|
| `aws_region` | `AWS_REGION` | ✅ |
| `sidecar_token` | `SIDECAR_TOKEN` | ✅ (secret) |
| `opensanctions_api_key` | `OPENSANCTIONS_API_KEY` | ✅ (secret) |
| `dynamodb_*_table` (9 tables) | `DYNAMODB_*_TABLE` | ✅ all nine |
| `opensanctions_base_url` | `OPENSANCTIONS_BASE_URL` | ❌ (uses default `https://api.opensanctions.org` — fine) |
| `fuzzy_threshold` | `FUZZY_THRESHOLD` | ❌ (uses default 0.92 — fine) |

No name-mismatch crashes. One harmless extra env var (`DYNAMODB_SANCTIONS_HITS_TABLE`) is injected but Python doesn't read it.

---

## B. Items needing Terraform agent action

### B1. Image build/push outside Terraform (recommended)

Current flow: `null_resource.sidecar_image_build` runs `docker buildx build --platform linux/amd64 --push` via `local-exec`. Problems:
- Needs Docker + buildx on the Terraform runner.
- Skips silently when state hashes haven't changed — image drift hard to detect.
- Breaks if Terraform runs in a remote backend / CI without a Docker socket.

**Proposed fix:** add `python-backend/scripts/build_and_push.sh` (or a Makefile target):
```bash
#!/usr/bin/env bash
set -euo pipefail
REGION=${AWS_REGION:-eu-central-1}
ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
REPO="${ACCOUNT}.dkr.ecr.${REGION}.amazonaws.com/launchlens-sidecar"
aws ecr get-login-password --region "$REGION" | docker login --username AWS --password-stdin "$REPO"
docker buildx build --platform linux/amd64 --push -t "$REPO:latest" .
```
Then gate the `null_resource` behind `count = var.build_image_inline ? 1 : 0` (default `false`) so CI can use the script instead.

### B2. ECS Express provider version

`ecs_sidecar.tf` uses `aws_ecs_express_gateway_service` — requires **hashicorp/aws ≥ 6.23**. Verify `versions.tf` pins this version or higher.

### B3. IAM least-privilege cleanup (non-blocking)

In `ecs_sidecar.tf` `DynamoWriteSanctionsAuditEvidence` policy — scope Resource to just `sanctions-hits`. Remove `audit-log` and `evidence` ARNs. Python grep confirmed no writes to those tables.

**Why this matters:** Java's audit-log chain-of-hashes relies on `AuditLogService.append` being the only writer. If Python ever writes directly (even by accident), it breaks the `prevHash`/`entryHash` invariant. Tightening the IAM prevents that class of bug.

### B4. Manual post-apply steps to document

Terraform can't do these — leave in a runbook or README:

1. **Populate OpenSanctions API key** before or at apply:
   ```bash
   terraform apply -var="opensanctions_api_key=<real-key>"
   ```
   Or post-apply:
   ```bash
   aws secretsmanager put-secret-value \
     --secret-id launchlens-opensanctions \
     --secret-string '<real-key>'
   aws ecs update-service --cluster default --service launchlens-sidecar --force-new-deployment
   ```

2. **Seed sanctions Dynamo** (optional — without it, local fuzzy match is empty, fallback to OpenSanctions API only):
   ```bash
   cd python-backend
   python scripts/normalize_sanctions.py --source ofac_sdn --seed-dynamo --aws-region eu-central-1
   ```

3. **Smoke test:**
   ```bash
   ENDPOINT=$(terraform output -raw sidecar_url)
   curl "$ENDPOINT/health"
   # expect {"status":"UP"}

   TOKEN=$(aws secretsmanager get-secret-value --secret-id launchlens-sidecar-token --query SecretString --output text)
   curl -X POST "$ENDPOINT/sanctions/screen" \
     -H "X-Sidecar-Token: $TOKEN" -H "Content-Type: application/json" \
     -d '{"session_id":"smoke-1","counterparties":[{"name":"Acme LLC","country":"US","type":"company"}]}'
   # expect {"results":[{"counterparty":{...},"match_status":"clear","hits":[]}]}
   ```

### B5. `ecs_task_execution_secrets` policy duplication risk

`ecs_express.tf:6-23` defines `aws_iam_role_policy.ecs_task_execution_secrets`. Both `ecs_express.tf` and `ecs_sidecar.tf` have `depends_on` referencing it. If the resource is defined once and referenced twice via `depends_on`, fine — but confirm it's not duplicated in both files. If duplicated, rename one or consolidate.

### B6. Clean up dead `sidecar_base_url` variable

`variables.tf:37` declares `sidecar_base_url`, `terraform.tfvars` sets it to `""`. After the `ecs_express.tf:121` rewrite to the live ingress endpoint, nothing reads this var. Two-line delete (both files) to silence `terraform plan` noise.

### B7. Terraform state in git (flag for decision, not action)

`terraform.tfstate` is committed. For a hackathon shared-deploy scenario this is risky — two people can't `terraform apply` simultaneously without corrupting state. Consider moving to an S3 remote backend if more than one person will deploy.

---

## C. Contract between Java and Python (verified working)

Java `SidecarClient` ↔ Python sidecar endpoints all match at wire level. Don't break these.

- **Auth:** header `X-Sidecar-Token: <token>` on every authenticated call. Both sides agree on this header name.
- **POST `/sanctions/screen`**: request `{session_id, counterparties:[{name, country, type}], brief_text}` → response `{results:[{counterparty:{name,country,type}, match_status:"clear|flagged|under_review", hits:[{list_source, entity_name, aliases[], match_score, list_version_timestamp}], entity_metadata:{}}]}`. Java parser ignores `aliases`, `entity_metadata`, `list_version_timestamp` — safe.
- **GET `/proof-tree/{mapping_id}`** and **GET `/compliance-map/{session_id}`**: return `{nodes:[...], edges:[...]}` — matches Java `GraphDAG` model.
- **GET `/health`**: returns `{"status":"UP"}`. Java only checks HTTP status code, ignores body.

### Dormant mismatch (safe for now, known risk)

**POST `/evidence/hash`** contract diverges:
- **Python expects:** `multipart/form-data` with fields `file`, `content_type`, `related_mapping_id`.
- **Java `SidecarClient.hashEvidence(byte[], String)` sends:** raw bytes with the content-type set as the HTTP Content-Type header.

Java no longer calls this method — Track B replaced the whole evidence-hash path with S3 Additional Checksums (`S3PresignHelper` signs uploads with `checksumAlgorithm(SHA256)`, `EvidenceService.hashFromS3` reads via `HeadObject + ChecksumMode.ENABLED`). So the endpoint mismatch is dormant. If anyone ever re-enables the sidecar hash path in Java, they'll get a 422 until the method is rewritten to send multipart.

---

## D. Open questions for decision

1. **ECS Express ingress visibility:** the sidecar's Express gateway endpoint is public internet. Only protection is the bearer token. For a hackathon demo, fine. For post-hackathon, consider an internal ALB or VPC-only gateway.
2. **`variables.tf:37` `sidecar_base_url`:** confirm OK to delete (dead after the `ecs_express.tf:121` rewire).
3. **`aws` provider version pin:** what does `versions.tf` currently require? If < 6.23, `aws_ecs_express_gateway_service` won't resolve.
4. **Single-vs-shared cluster:** Track A put the sidecar on `cluster = "default"` alongside Java. If the cluster is provisioned elsewhere, make sure both services point at the same physical ECS cluster ARN.

---

## E. Reference — deploy sequence

```
1. (dev, one-time) write python-backend/scripts/build_and_push.sh; gate null_resource
2. (dev) ensure versions.tf has hashicorp/aws >= 6.23
3. (dev) bash python-backend/scripts/build_and_push.sh    # pushes image to ECR
4. (dev) terraform apply -var="opensanctions_api_key=<real>"   # creates ECR, secrets, ECS service
5. (dev) python python-backend/scripts/normalize_sanctions.py --source ofac_sdn --seed-dynamo
6. (dev) curl $(terraform output -raw sidecar_url)/health   # smoke
7. (dev) hit Java /actuator/health — confirm SidecarHealthIndicator is UP
```
