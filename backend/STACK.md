# Stack

## Runtime

| Concern | Choice |
|---|---|
| Language | Java 25 |
| Build | Maven |
| Framework | Spring Boot 4.0.5 |
| JSON | Jackson 3 — `tools.jackson.databind.*` (Spring Boot 4 default; Jackson 2 `com.fasterxml.jackson.*` must not be used) |
| Validation | `jakarta.validation.constraints.*` + `@Valid` |
| Reactive/HTTP client | Spring `WebClient` (for the sidecar) |
| Container image | Jib 3.5.1 (`./mvnw jib:build`) — no Docker daemon |
| Fargate task memory | 2 GB; JVM `-XX:InitialRAMPercentage=50.0 -XX:MaxRAMPercentage=75.0` (no fixed `-Xmx`) |

## AWS SDK

AWS SDK v2 (`software.amazon.awssdk.*`). All clients are `@Bean`s in `config/AwsConfig.java`.

## AWS services used

- **ECS Express Mode** — Fargate + auto ALB + managed TLS cert on `*.ecs.eu-central-1.on.aws`
- **S3** — `launchlens-uploads` bucket with `documents/`, `evidence/`, `reports/`, `transcribe-results/` prefixes; 3 KB source buckets
- **DynamoDB** — one table per entity (`DYNAMODB.md`); GSIs for reverse lookups
- **Bedrock Runtime** — Claude Opus 4.7 / Sonnet 4.6 / Haiku 4.5 via `eu.anthropic.*` cross-region inference profile
- **Bedrock Knowledge Bases ×3** — regulations, policies, controls; S3 Vectors backend (1024-dim float32, cosine)
- **Textract async** — `StartDocumentTextDetection` + polling; S3-direct, zero bytes through the JVM
- **Transcribe async** — `StartTranscriptionJob` + polling; outputs JSON to `s3://launchlens-uploads/transcribe-results/`
- **Secrets Manager** — OpenSanctions API key + auto-generated sidecar shared token
- **ECR** — container registry
- **CloudWatch Logs** — 7-day retention on `/ecs/launchlens-backend`

## Models

Enum `model/enums/BedrockModel.java`:

```
OPUS   — eu.anthropic.claude-opus-4-7-v1:0              (max 8192 tokens)
SONNET — eu.anthropic.claude-sonnet-4-6-v1:0            (max 4096)
HAIKU  — eu.anthropic.claude-haiku-4-5-20251001-v1:0    (max 2048)
```

Cross-region inference profile IDs (the `eu.` prefix is required). Foundation-model ARNs are also granted to the task role via wildcard.

## Region

All resources in `eu-central-1` (Frankfurt), including Bedrock runtime + KBs + S3 Vectors. No cross-region traffic at runtime.

## Not used / stretch

- **Polly** — stretch goal, not in current implementation. Report is PDF-only.
- **Claude vision PDF path** — rejected in favor of Textract async (100-page Converse cap vs 3000-page Textract limit; user corpus has 150+ and 400+ page regs).
- **Cognito / Spring Security** — no auth on public endpoints; CORS-locked to Amplify origins.
- **True token-level SSE streaming** (StreamingJsonParser) — per-record SSE events are emitted at stage granularity after each Bedrock call returns; good enough for the live-graph UX.
