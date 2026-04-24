# LaunchLens Infrastructure

Terraform configuration for the LaunchLens hackathon backend, deploying to AWS `eu-central-1`.

## Prerequisites

1. **AWS CLI v2** — `aws --version` should report 2.x. Required for `null_resource` fallbacks and ECR auth.
2. **AWS credentials** — `aws configure --profile launchlens` (or `default`). The profile must have admin-level permissions.
3. **Bedrock model access** — request in the AWS Console under Bedrock → Model access, `eu-central-1`:
   - Anthropic Claude Opus 4.7, Sonnet 4.6, Haiku 4.5
   - Amazon Titan Embeddings v2
   - Approval can take hours — start early.
4. **Terraform ≥ 1.9** — `choco install terraform` or download from hashicorp.com.
5. **jq** — used by some local-exec provisioners.
6. **Git Bash / MSYS2** — `local-exec` provisioners use bash syntax. Run Terraform from Git Bash, not cmd.exe.
7. **Seed docs** — place documents under `java-backend/seed/{regulations,policies,controls}/` before applying. The upload is skipped if directories are empty.
8. **`terraform.tfvars`** — copy `terraform.tfvars.example` and fill in your values (gitignored).

## Commands

```bash
cd java-backend/infra

# First time only — downloads provider plugins:
terraform init

# Preview all changes before touching AWS:
terraform plan

# Deploy everything (takes ~10 min including Jib build + ECS startup):
terraform apply

# Read the backend URL:
terraform output backend_url

# Read all outputs:
terraform output

# Tear down all resources:
terraform destroy
```

## What This Creates

- **IAM** — task role (DDB/S3/Bedrock/Transcribe/Polly/Textract/Secrets), Bedrock KB role, execution-role secrets policy
- **DynamoDB** — 9 on-demand tables (`sessions`, `obligations`, `controls`, `mappings`, `gaps`, `sanctions-hits`, `evidence`, `sanctions-entities`, `audit-log`)
- **S3** — uploads bucket (versioned, CORS, SSE, no public access) + 3 KB source buckets (`regulations`, `policies`, `controls`) + seed document upload
- **S3 Vectors** — 1 vector bucket + 3 indexes (`regulations-idx`, `policies-idx`, `controls-idx`), float32/cosine/1024-dim
- **Secrets Manager** — OpenSanctions API key + auto-generated sidecar shared token
- **ECR** — `launchlens-backend` repository with scan-on-push
- **Jib build** — Maven Jib pushes the Spring Boot image to ECR (no Docker daemon needed); re-triggered by hash of `src/` + `pom.xml`
- **Bedrock KBs** — 3 Knowledge Bases (regulations, policies, controls) with S3 Vectors storage + S3 data sources + initial ingestion
- **ECS Express Mode** — Fargate task (1 vCPU / 2 GB) + auto-ALB + managed TLS cert + `*.ecs.eu-central-1.on.aws` URL; rolling-update controller
- **CloudWatch** — log group `/ecs/launchlens-backend` with 7-day retention
- **CloudFront** — optional fallback distribution (disabled by default; toggle with `-var="enable_cloudfront_fallback=true"`)

## Troubleshooting

**Bedrock model access pending**
Apply will fail at the KB ingestion step with `AccessDeniedException`. Wait for model access approval in the Bedrock console, then `terraform apply` again.

**`AccessDeniedException` on Bedrock calls**
Check that `aws.bedrock.region` in `application.yaml` is `eu-central-1` and that the IAM task role has the correct inference profile ARNs (`eu.anthropic.*`).

**Stale `.terraform.lock.hcl`**
If `terraform init` complains about provider hash mismatches: `rm .terraform.lock.hcl && terraform init`.

**Jib push auth failure**
The ECR login token is valid for 12 hours. If you see `401 Unauthorized` during `jib:build`, taint the build resource to force a re-run:
```bash
terraform taint null_resource.jib_build
terraform apply
```

**ECS service stuck in PROVISIONING**
Express Mode provisions the ALB in the background. Check:
```bash
aws ecs describe-services --cluster default --services launchlens-backend \
  --query 'services[0].deployments'
```
If stuck >5 min, check the task stopped reason:
```bash
aws ecs list-tasks --cluster default --service-name launchlens-backend
aws ecs describe-tasks --cluster default --tasks <task-arn>
```

**`/tmp/kb_id_*.txt` not found**
The KB ID files are written to `/tmp` during apply. If you run `terraform apply` in a new shell after a previous partial apply, the files may be missing. Taint the KB null_resources to re-create:
```bash
terraform taint 'null_resource.bedrock_kb["regulations"]'
# repeat for policies, controls
terraform apply
```

**Toggle CloudFront fallback**
```bash
terraform apply -var="enable_cloudfront_fallback=true"
terraform output cloudfront_url
# Set VITE_API_BASE on the Amplify environment to the cloudfront_url value.
```

**Force re-ingestion of KB documents**
```bash
terraform taint 'null_resource.kb_ingestion["regulations"]'
terraform apply
```

## Costs (24h hackathon estimate)

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

Dominant cost is Bedrock tokens. Use Haiku for extraction, Sonnet for mapping, Opus only for final synthesis.

## Teardown

```bash
terraform destroy
```

All resources are destroyed in reverse-dependency order. Notes:
- The CloudFront distribution (if enabled) takes ~15 minutes to fully disable after `terraform destroy` returns.
- S3 buckets use `force_destroy = true` — all objects are deleted automatically.
- Secrets Manager secrets use `recovery_window_in_days = 0` — immediate permanent deletion.
- `/tmp/kb_id_*.txt` files are cleaned up by the null_resource destroy provisioners.

## Sidecar

The Python FastAPI sidecar runs as a second ECS Express service alongside the Java backend. It handles:
- **Sanctions screening** — `POST /sanctions/screen` — calls OpenSanctions API then falls back to DynamoDB `sanctions-entities` table.
- **Evidence hashing** — `POST /evidence/hash` — returns SHA256, size, and content type for uploaded bytes.
- **Proof-tree generation** — `GET /proof-tree/{mapping_id}` — builds and returns a DAG JSON from DynamoDB mappings/obligations/controls/evidence.

The sidecar image is built with `docker buildx build --platform linux/amd64` (standard Docker CLI — no Jib). Docker must be running locally before `terraform apply`.

Get the live sidecar URL after apply:
```bash
terraform output sidecar_url
terraform output sidecar_ecr_repository_url
```

Force a sidecar image rebuild (e.g. after Python code changes):
```bash
terraform taint null_resource.sidecar_image_build
terraform apply
```
