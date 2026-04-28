# Backend — Entry Point

> Start here. Each section points at the detailed document.

## Stack

- **Java 25** + Maven + **Spring Boot 4.0.5** (Jackson 3 — `tools.jackson.databind.*`, never `com.fasterxml.jackson.*`)
- **AWS SDK v2** for all AWS interactions
- **Lombok** everywhere — `CODE_PATTERNS.md`
- Fargate target: 2 GB RAM, `-XX:MaxRAMPercentage=75.0` (no fixed `-Xmx`)

Detail: `STACK.md`

## What this service does

Ingest regulation + policy docs → extract obligations + controls → compute mappings → score gaps → screen sanctioned counterparties → ground-check against retrieved evidence → emit a narrative + PDF report. Every stage streams events over SSE so the frontend can render a live graph.

Core differentiators baked in:
- **Document library** — user docs are content-addressable (SHA-256 from S3), deduped across sessions. Re-running the same regulation is instant (no Textract, no Bedrock extraction). See `DOCUMENTS_API.md`.
- **Zero-heap PDF ingest** — Textract async reads S3 directly, no PDF bytes transit the JVM.
- **Zero-heap evidence hashing** — S3 Additional Checksums compute SHA-256 server-side; we read it via `HeadObject`.
- **Chain-of-hashes audit log** — every mapping decision appended to a tamper-evident chain.
- **Prompt cache on Bedrock** — cached regulation prefix. Cache metrics logged on both sync and streaming paths.
- **Mapping cache** — deterministic `MAP-<sha256(obl#ctrl):16>` IDs; a pair mapped once is reused forever.

## Layout

```
config/         ← AWS beans, DynamoDbConfig (TableSchema beans), CorsConfig, SecurityConfig, health/
controller/     ← one controller per resource; common/ErrorController for legacy types
web/            ← GlobalExceptionHandler (@Valid + IllegalStateException)
client/         ← SidecarClient (Python FastAPI)
service/        ← business logic
  pipeline/     ← PipelineOrchestrator + stage/ (Ingest, Extract*, Map, Gap, Sanctions, GroundCheck, Narrate)
  sse/          ← SseEmitterService
repository/     ← one per DynamoDB table
model/          ← @DynamoDbBean entities; model/enums/BedrockModel
dto/request     ← @Valid'd inputs
dto/response    ← DTOs + events/ (SSE payloads)
helper/         ← S3PresignHelper, IdGenerator, mapper/
exception/      ← custom RuntimeExceptions
```

Detail: `STRUCTURE.md`

## Conventions

- Constructor injection only (`@RequiredArgsConstructor`). Never `@Autowired`.
- No `throws` on service/controller signatures — wrap in `RuntimeException`; handled by `ErrorController` + `GlobalExceptionHandler`.
- No comments unless intent is non-obvious.
- Presigned URLs: always via `S3PresignHelper`, never inline in a controller.
- SSE: `SseEmitterService.send(sessionId, eventName, data)` emits a **native named event**. Heartbeat every 15 s.

Detail: `CODE_PATTERNS.md`

## API

REST at `/api/v1/**`. SSE at `/api/v1/events`. Full endpoint list: `API.md`. Documents upload + library contract: `DOCUMENTS_API.md`.

## Persistence

One DynamoDB table per entity; Enhanced Client via `TableSchema.fromBean(...)`. GSIs for reverse lookups (`document-id-index`, `session_id-timestamp-index`, `kind-last-used-at-index`). Deterministic IDs for mapping dedup.

Detail: `DYNAMODB.md`

## Exceptions

Two `@ControllerAdvice` classes:
- `controller/common/ErrorController.java` — legacy exception types (`SessionNotFoundException`, `SidecarCommunicationException`, etc.)
- `web/GlobalExceptionHandler.java` — `MethodArgumentNotValidException` (400) + `IllegalStateException` (409)

All error bodies: `{message, correlationId}`. Detail: `EXCEPTIONS.md`.

## Python sidecar

Python FastAPI handles sanctions screening (OpenSanctions + local DynamoDB fallback), proof-tree DAG assembly, and compliance-map DAG. Java calls via `SidecarClient` (`WebClient`), never exposed to browsers. Evidence hashing and OCR are **no longer** sidecar responsibilities — Java does both directly (S3 checksum; Textract/Transcribe SDK).

Detail: `SIDECAR.md`

## Prompt cache

Regulation prefix cached via `cache_control: {type: "ephemeral"}`. Tool-definition JSON is byte-identical across calls (loaded once at startup). Cache tokens logged by both `BedrockService` and `BedrockStreamingService`.

Detail: `PROMPT_CACHE.md`

## Deployment

ECS Express Mode (Fargate) in `eu-central-1` behind an auto-provisioned ALB with AWS-managed TLS on a `*.ecs.eu-central-1.on.aws` URL. Jib builds the container image (no Docker daemon). Terraform owns infra.

ADR: `DEPLOYMENT.md` — Infra walkthrough: `INFRA_GUIDE.md` — Quick-start: `infra/README.md`.
