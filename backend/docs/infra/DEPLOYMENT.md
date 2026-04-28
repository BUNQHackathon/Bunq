# LaunchLens — AWS Deployment Plan (Terraform)

## Context

LaunchLens is a 24h hackathon compliance copilot. The Spring Boot 4.0.5 / Java 25 orchestrator at `D:\Programs\Java\Java Project\backend\java-backend\` is code-complete but has **zero infrastructure** deployed. This plan takes it from laptop-only to a public HTTPS URL the Amplify-hosted React frontend can call, with Bedrock + DynamoDB + S3 + 3 Knowledge Bases wired in — using **Terraform** as the IaC layer so the result is production-ready from day one (state tracking, drift detection, `plan` preview, one-command teardown).

### Compute: Amazon ECS Express Mode

**App Runner stops accepting new customers on 2026-04-30** (8 days away). AWS's recommended replacement is **Amazon ECS Express Mode** (Fargate-backed, GA Nov 2025). One resource call provisions the Fargate service + shared ALB + HTTPS listener + AWS-managed TLS cert + free `*.ecs.<region>.on.aws` URL + VPC + auto-scaling + CloudWatch. No CloudFront, no ACM, no Route 53, no domain purchase.

The user's AWS account already has `ecsInfrastructureRoleForExpressServices` and `ecsTaskExecutionRole` from a prior Express Mode experiment 3 days ago — Terraform will **reuse those via `data` blocks** instead of recreating.

### IaC: Terraform from day one

User chose Terraform over bash/CDK for **production-ready** habits + transferable skill. Trade-off: ~4h of Terraform learning curve, paid back immediately by `terraform plan` preview (safer than guessing with bash), clean `terraform destroy`, and industry-standard skill applicable at every future job.

**Decision summary (v1):** ECS Express Mode (Fargate) + Amplify + Terraform + S3 Vectors KB backend, everything in `eu-central-1` via `eu.anthropic.*` cross-region inference profile. **Rolling-update deployment controller** (not canary).

### How this dodges every past pain point

| Prior pain | How v1 sidesteps it |
|---|---|
| Mixed Content (HTTPS → HTTP) | Express Mode is HTTPS-native on `*.ecs.<region>.on.aws` |
| Manual ALB + target groups + listener rules | Express Mode provisions them (Terraform abstracts even further) |
| ACM cert request + DNS validation | AWS-managed cert on `on.aws`, no ACM surface |
| CloudFront `AllViewerExceptHostHeader` workaround | No CloudFront in path; Spring CORS directly |
| WAF blocking multipart PDFs | No WAF attached; see gotcha below if added |
| Canary/CodeDeploy complexity | Rolling-update controller, zero extra resources |
| Amplify `applications:` monorepo key | Frontend is a separate repo — flat `amplify.yml` |
| Root user blocked from Bedrock | Use existing admin IAM user |
| Manual "which resource depends on which" | Terraform graph handles it |

## Architecture (ASCII)

```
       Browser (HTTPS)
             |
             v
   Amplify Hosting (React/Vite)
             |  VITE_API_BASE=https://<svc>.ecs.eu-central-1.on.aws
             v
   ECS Express Mode service: launchlens-backend
     = Fargate tasks (1 vCPU / 2 GB)
     + auto-provisioned ALB + HTTPS listener + managed cert
     + auto-scaling + CloudWatch + VPC
     + rolling-update deployment controller
     + task role (DDB/S3/Bedrock/Transcribe/Polly/Textract/Secrets)
       |           |               |               |              |
       v           v               v               v              v
   DynamoDB   S3 uploads   Bedrock runtime   Transcribe/    (call to sidecar
   9 tables   (versioned,    + 3 KBs (S3     Polly/           over HTTPS with
   PAY_PER_   presigned      Vectors) in     Textract         shared-secret;
   REQUEST    PUTs)          eu-central-1                    sidecar = separate
                             via eu.anthropic.*               Express service)
                             inference profile

   Side: Secrets Manager, CloudWatch Logs (7d), ECR
```

## Three key choices

1. **Terraform over bash/CDK.** Production-ready from day one: state tracking, drift detection, `terraform plan` preview, one-command destroy, transferable industry-standard skill. CDK was considered but carries CloudFormation rollback hell and Node.js toolchain; Terraform's `plan` UX beats CDK's `diff` for learning-while-shipping.
2. **ECS Express Mode over classic ECS/App Runner/EC2.** App Runner sunsetting; classic ECS demands manual VPC/ALB/ACM (the pain we're avoiding); EC2 needs SSL termination. Express Mode is one `aws_ecs_service` Terraform resource in — HTTPS URL out.
3. **S3 Vectors (GA Dec 2025, eu-central-1) over OpenSearch Serverless.** OSS minimum ~$350/mo idle; S3 Vectors is pay-per-use (<$0.50 hackathon). Bedrock KB supports it natively. Trade-off: semantic-only, no hybrid search — LaunchLens reranks with Sonnet anyway.

## Terraform provider coverage — important flags

Some resources are **very recent** (Nov 2025 / Dec 2025). If the AWS provider lacks first-class support when we run `terraform init`, fall back to `null_resource` + `local-exec` wrapping `aws ...` CLI (standard Terraform pattern for bleeding-edge features):

| Resource | Expected Terraform support (April 2026) | Fallback |
|---|---|---|
| ECS Express Mode service | Likely via `aws_ecs_service` with Express config block or similar | `null_resource` running `aws ecs create-service --express-configuration ...` |
| S3 Vectors bucket + index | May or may not be in AWS provider yet | `null_resource` running `aws s3vectors create-vector-bucket/create-index` |
| Bedrock KB with `S3_VECTORS` storage config | `aws_bedrockagent_knowledge_base` exists; the `s3Vectors` storage option may be new | `null_resource` running `aws bedrock-agent create-knowledge-base` |
| Everything else (IAM, DynamoDB, S3, ECR, Secrets Manager, VPC, CloudWatch) | Fully supported for years | — |

These fallbacks stay managed by Terraform (state-tracked, destroyable) — they're just shell calls inside the graph. When provider support lands, it's a one-file rewrite with no data loss.

## Region & Bedrock strategy

All services in **`eu-central-1`** (Frankfurt):
- DynamoDB, S3, ECS Express, Amplify, Transcribe, Polly, Textract, Secrets Manager, ECR — native.
- Bedrock runtime + 3 KBs + S3 Vectors — also Frankfurt, using `eu.anthropic.claude-{opus-4-7,sonnet-4-6,haiku-4-5}-*` cross-region inference profile IDs. EU-resident data. Titan Embeddings V2 available.
- **Spring config edit:** flip `aws.bedrock.region` from `us-east-1` to `eu-central-1` in `application.yaml`; add `aws.bedrock.model-ids.{opus,sonnet,haiku}` keys populated from env, with relaxed binding so Terraform-injected `AWS_BEDROCK_MODEL_IDS_*` env vars take effect.

## Prerequisites

1. **IAM admin user** (already logged in via `aws configure` — profile `launchlens` or default).
2. **Bedrock model access requested** in `eu-central-1`: Opus 4.7, Sonnet 4.6, Haiku 4.5, Titan Embeddings v2. **Start at T=0** — approval can take hours.
3. ~~Docker Desktop~~ — **not needed**. Jib builds and pushes images without a Docker daemon.
4. **AWS CLI v2** (latest, for the CLI fallbacks + `aws ecr get-login-password` for Jib auth).
5. **Terraform CLI** — install via `choco install terraform` or download from hashicorp.com. Confirm `terraform -version` ≥ 1.9.
6. **`jq`** installed.
7. **Seed docs** under `java-backend/seed/{regulations,policies,controls}/`.

## Pre-deploy Spring Boot edits (by Sonnet subagent)

1. **`pom.xml`** — add `spring-boot-starter-actuator` (for `/actuator/health` target-group probe). Also add **`com.google.cloud.tools:jib-maven-plugin:3.5.1`** with image coordinate `${env.ECR_IMAGE}` so Terraform can inject the ECR repo URL at build time. **No Dockerfile is used** — Jib builds and pushes the OCI image directly from Maven, with smart per-dependency layering and native ECR auth. Avoids a Docker daemon dependency on Windows/WSL2.
2. **`application.yaml`** — flip bedrock region to `eu-central-1`; add `aws.bedrock.model-ids.*` keys; allow env overrides; expose `/actuator/health`.
3. **`CorsConfig.java`** (new) — allow `https://*.amplifyapp.com` + `http://localhost:5173`.
4. **`SidecarClientConfig.java`** — inject `X-Sidecar-Token: ${sidecar.token}` header on WebClient.

## Terraform project layout

All under `java-backend/infra/`:

| File | Purpose |
|---|---|
| `versions.tf` | Required Terraform ≥ 1.9; AWS provider ~> 5.70 (or whichever is current April 2026) |
| `providers.tf` | `provider "aws" { region = var.region; profile = var.aws_profile }` |
| `backend.tf` | v1: local state. `# TODO migrate to S3+DDB lock post-hackathon` |
| `variables.tf` | `project_prefix = "launchlens"`, `region = "eu-central-1"`, `bedrock_region = "eu-central-1"`, `aws_profile`, `image_tag`, `amplify_origin`, `sidecar_base_url`, `enable_cloudfront_fallback = false` |
| `locals.tf` | `dynamodb_tables = [9 names]`, `kb_buckets = {regulations,policies,controls}`, `model_ids = {opus,sonnet,haiku}` |
| `outputs.tf` | `backend_url`, `ecr_repo_url`, `vite_api_base`, KB IDs, bucket names |
| `iam.tf` | 3 roles: task-execution (reuses existing `ecsTaskExecutionRole` via `data "aws_iam_role"`), task-role (new, inline policy for DDB/S3/Bedrock/Transcribe/Polly/Textract/Secrets), Bedrock-KB role (new). Express infrastructure role reused via `data` block. |
| `dynamodb.tf` | 9 tables via `resource "aws_dynamodb_table" "this" { for_each = toset(local.dynamodb_tables) ... }` |
| `s3.tf` | Uploads bucket (versioning + CORS + Block Public Access + SSE) + 3 KB source buckets via `for_each`; `aws_s3_object` resources to sync seed docs |
| `secrets.tf` | 2 `aws_secretsmanager_secret` entries (opensanctions key + sidecar token) |
| `s3_vectors.tf` | Vector bucket + 3 indexes — likely `null_resource` wrapping CLI until provider supports it |
| `bedrock_kb.tf` | 3 KBs + data sources + ingestion trigger — may be `null_resource` for the S3_VECTORS storage config |
| `ecr.tf` | `aws_ecr_repository` with scan-on-push |
| `jib_build.tf` | `null_resource` with `local-exec` running `./mvnw -B -DskipTests compile jib:build`, env `ECR_IMAGE=<ecr_repo_url>:<image_tag>`, ECR password via `-Djib.to.auth.password=$(aws ecr get-login-password)`. Triggered by hash of `src/` + `pom.xml`. `depends_on = [aws_ecr_repository.this]`. No Docker daemon required. |
| `ecs_express.tf` | Task definition + Express service; `deployment_controller = ECS`; `deployment_configuration = { min 100 / max 200 }`; env vars from outputs of other resources; `depends_on = [null_resource.jib_build]` |
| `cloudwatch.tf` | Log group `/ecs/launchlens-backend` with 7-day retention |
| `cloudfront.tf` | OPTIONAL, wrapped in `count = var.enable_cloudfront_fallback ? 1 : 0` — only created if direct Express URL misbehaves |
| _no build script needed_ | Jib runs directly from Maven — `./mvnw -B jib:build` with env `ECR_IMAGE` set by Terraform. ECR password passed via `-Djib.to.auth.password` on the CLI. |
| `README.md` | `terraform init`, `terraform plan`, `terraform apply`, `terraform destroy`, troubleshooting |
| `.gitignore` | `.terraform/`, `terraform.tfstate*`, `*.tfvars` (if secrets present), `.env.out` |
| `terraform.tfvars.example` | Committed template; real `terraform.tfvars` is gitignored |

## Deployment workflow (the user-facing sequence)

```bash
cd java-backend/infra
# First time only:
terraform init

# Review what will be created:
terraform plan

# Apply (creates everything including Docker build+push via null_resource):
terraform apply

# Read outputs:
terraform output backend_url
```

The user runs **two commands** (plan, apply) and gets a working URL. Compare to the bash alternative (10+ ordered scripts). This is why we picked Terraform.

## Phase-by-phase, what `terraform apply` does under the hood

- **Phase A (foundation)** — IAM roles, 9 DynamoDB tables, uploads S3 bucket + CORS, 2 Secrets Manager entries. All first-class Terraform resources.
- **Phase B (KB layer)** — 3 KB source buckets + seed doc upload, S3 Vectors bucket + 3 indexes, 3 Bedrock KBs + data sources + ingestion trigger, smoke-retrieve assertion.
- **Phase C (container)** — ECR repo + Jib build+push via `null_resource` (triggered by hash of `src/` + `pom.xml`, so re-apply rebuilds on code change). No Docker daemon needed.
- **Phase D (ECS Express)** — Register task definition, create Express service with rolling-update controller, wait for healthy. Terraform auto-resolves all the dependencies.
- **Phase E (sidecar)** — Deferred; sidecar is a separate repo with its own Terraform module.
- **Phase F (frontend)** — Separate repo (Amplify). `VITE_API_BASE=<terraform output backend_url>`.
- **Phase G (observability)** — CloudWatch log group + retention, auto-captured Express logs.
- **Phase H (teardown)** — `terraform destroy` — one command, reverse-order, all resources gone. The "IaC proof" is now `terraform destroy && terraform apply` round-tripping cleanly.

### Optional CloudFront fallback

If `https://<svc>.ecs.eu-central-1.on.aws` misbehaves from the Amplify frontend (repeat of the previous "Amplify blocks" symptom):
```bash
terraform apply -var="enable_cloudfront_fallback=true"
```
This provisions a CloudFront distribution via `cloudfront.tf` (origin = Express URL, `Managed-CachingDisabled` + `Managed-AllViewerExceptHostHeader`, no WAF), outputs a `*.cloudfront.net` URL. Flip `VITE_API_BASE` on Amplify.

## ⚠️ WAF gotcha — if WAF is ever attached

Do not attach a WAF Web ACL to the Express ALB or any fallback CloudFront in v1. If a stakeholder insists:
- AWS Managed `CommonRuleSet` + `SQLiRuleSet` treat PDF bytes in `multipart/form-data` as SQL-injection — `403`'d `/api/v1/docs` last time.
- Set `SizeRestrictions_BODY` + SQLi body rules to **Count**, or scope-down uploads (`/sessions/*/upload`, `Content-Type: multipart/form-data`).
- ALB WAF inspection limit 8–64 KB; raise AND scope-down, not either-or.

## Verification

- `terraform plan` → shows expected resources; no surprise destroys.
- `terraform output backend_url` → `https://<svc>.ecs.eu-central-1.on.aws`.
- `curl https://<backend_url>/actuator/health` → `{"status":"UP"}`.
- `aws ecs describe-services --query 'services[0].deployments[0].rolloutState'` → `COMPLETED`.
- `aws dynamodb scan --table-name launchlens-sessions --limit 1` → `Count=0`.
- **S3 presign flow:** backend returns presigned PUT → upload test PDF → object in bucket + row in `launchlens-sessions`.
- **KB retrieve:** `aws bedrock-agent retrieve --knowledge-base-id $(terraform output -raw kb_regulations_id) --retrieval-query '{"text":"GDPR article 5"}'` → non-empty.
- **Rolling update smoke:** edit code, `terraform apply` → new image pushed → ECS rolls tasks (old+new coexist, then old terminates); health stays green.
- **End-to-end:** Amplify frontend → create session → upload → observe SSE events → verdict.
- **`terraform destroy` → `terraform apply` round-trip** rebuilds identically = real IaC confirmed.

## Cost envelope (24h hackathon)

| Item | Cost |
|---|---|
| ECS Express (Fargate 1 vCPU / 2 GB, 24h) | ~$1.15 |
| Auto-provisioned ALB (24h) | ~$0.55 |
| Sidecar Express (0.5 vCPU / 1 GB, 24h) | ~$0.60 |
| DynamoDB on-demand | $0.05 |
| S3 storage | $0.03 |
| S3 Vectors | < $0.01 |
| Bedrock Claude tokens (Haiku-tiered) | $8–15 |
| Titan Embeddings (one-time) | < $0.10 |
| Transcribe / Polly / Textract | < $1 |
| Amplify, ECR, Secrets, CloudWatch | < $1 |
| **Total** | **~$12–20** |

Dominant cost = Bedrock tokens. Tier aggressively: Haiku for extraction, Sonnet for mapping, Opus for final synthesis with cached regulation prefix.

## Plan-to-project distribution

Once this plan is approved, copy it to **`D:\Programs\Java\Java Project\backend\java-backend\DEPLOYMENT.md`** so it lives alongside `BACKEND.md`, `API.md`, `DYNAMODB.md`, etc. in the project repo. The `.claude/plans/` copy stays as the approval-of-record.

## Learning notes (AWS + Terraform concepts)

- **Terraform state file (`terraform.tfstate`)** = the source of truth for "what resources exist and what are their current attributes." For solo hackathon work, local state is fine (gitignored). For team work, migrate to `backend "s3"` with a DynamoDB lock table — one command: `terraform init -migrate-state`.
- **`terraform plan`** = the killer feature. Before any change, Terraform prints a diff: `+` created, `-` destroyed, `~` modified. Read it before every `apply`. Catches mistakes that bash scripts would silently execute.
- **`for_each` + `toset()` / map** = Terraform's way to avoid copy-paste. 9 DynamoDB tables = one `resource` block with a `for_each` — add/remove items in the local list, Terraform adds/removes tables.
- **`null_resource` + `local-exec`** = escape hatch for resources the AWS provider doesn't cover yet (S3 Vectors, bleeding-edge features). Still tracked by Terraform state, still destroyed by `terraform destroy`.
- **`data` block vs `resource` block** = `data` reads an existing resource (like your leftover `ecsTaskExecutionRole`), `resource` creates a new one. Use `data` to reuse AWS-created or manually-created items without importing them.
- **Task execution role vs task role** = execution role pulls ECR image + ships logs; task role is assumed by the running container for app-level AWS calls (DDB/Bedrock). Two trust principals (both `ecs-tasks.amazonaws.com`), two distinct permission boundaries.
- **ECS Express Mode under the hood** = not magic — it's Fargate + auto-ALB + auto-cert + auto-VPC + auto-scaling + log group. AWS runs the same primitives you would; Express wraps them in one API with sensible defaults. `*.ecs.<region>.on.aws` is an AWS-owned DNS zone with pre-issued managed TLS certs, saving you ACM + custom domain steps.
- **Cross-region inference profiles (`eu.anthropic.*`)** = virtual model IDs routing across EU regions. EU data residency, higher throughput, no extra charge.
- **Rolling update vs blue/green vs canary** = rolling replaces tasks in place (default ECS, simplest, hackathon choice). Blue/green provisions parallel new task set + swaps. Canary = blue/green with gradual traffic shift (needs CodeDeploy). User preference: rolling for tests.
- **Bedrock KB + S3 Vectors** = AWS wraps chunk → embed → store → retrieve in one API. S3 Vectors is 10× cheaper than OpenSearch Serverless at hackathon scale; semantic-only (no BM25 hybrid).
- **Presigned URLs** = short-lived signed S3 endpoints. Browser PUTs directly to S3 (bypasses your backend). No public bucket. Signature scopes key + verb + expiry.

## Open decisions (can pivot later without destroying state)

- **Frontend bridging** → v1 direct to Express URL. CloudFront behind a Terraform variable (`enable_cloudfront_fallback`), toggleable.
- **Sidecar privacy** → v1 public Express + shared-secret; v2 Cloud Map + private SGs.
- **Deployment controller** → v1 rolling; can move to ECS-native blue/green by flipping one Terraform attribute.
- **S3 Vectors semantic-only** → accepted; swap to OSS is a module-level change.
- **Terraform state backend** → v1 local; migrate to S3+DDB lock post-hackathon once multi-dev.
