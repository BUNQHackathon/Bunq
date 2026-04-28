**Prism: AI-Assisted Compliance Copilot for Neobank Expansion**

A multi-jurisdiction compliance prototype built during the bunq 7.0 hackathon (April 2026). 17 hours, four engineers. I owned the backend: pipeline orchestrator, Bedrock integration, audit-log chain, DynamoDB layer, and Terraform infrastructure.

**Problem**

A neobank entering a new jurisdiction has to read every applicable regulation, extract every legal obligation, map each obligation to an existing internal control, score uncovered gaps, screen counterparties, and assemble an audit-ready proof pack with verifiable citations. The work is mostly manual today.

Prism takes a product brief plus a list of jurisdictions and produces an audit-ready proof pack with a tamper-evident audit log. The model proposes obligation-to-control mappings; a human accepts, overrides, or edits. The model never picks the verdict colour.

**Pipeline**

Eight-stage `PipelineOrchestrator` over Spring Boot 4 / Java 25. Stage 1 is Ingest, with Textract async on S3 so zero PDF bytes pass through the JVM. Stages 2 and 3 run in parallel: Extract Obligations and Extract Controls, both on Claude Haiku 4.5 with tool-use. Stages 4 and 5 run in parallel: Sanctions Screen (Python sidecar) and Map Obligations-to-Controls (Claude Haiku 4.5). Stage 6 is Gap Analyze (Claude Haiku 4.5, `score_gap` tool). Stage 7 is Ground-Check (Amazon Nova Pro via Converse API, batches of 50). Stage 8 is Narrate (Claude Haiku 4.5, OpenPDF for the final report).

Each stage emits Server-Sent Events for live frontend progress. Stage completion is checkpointed to `Session.completedStages`, so failed runs resume from the last successful stage. Stage failures throw `PipelineStageException` carrying stage and cause, which an `@ControllerAdvice` translates to typed SSE error events without leaking stack traces. A correlation ID is injected into MDC at request entry and surfaces in every log line and error response.

**Key engineering decisions**

**1. Deterministic verdict in Java, not LLM.** RED, AMBER, GREEN is computed from five dimensions the model emits per gap (severity, likelihood, detectability, blast_radius, recoverability) with fixed weights: 0.4 times severity, plus 0.25 times likelihood, plus 0.15 times detectability, plus 0.10 times blast_radius, plus 0.10 times recoverability. The LLM proposes; Java decides. "The AI said so" is not an answer an auditor accepts. The human stays in the loop.

**2. Hash-chained audit log.** Every pipeline decision (`mapping_created`, `ground_check_failed`, `mapping_verified`) is appended to `launchlens-audit-log` with a SHA-256 `entry_hash` that incorporates `prev_hash`. Canonical-string format, alphabetically ordered: `action=...|actor=...|id=...|mappingId=...|payload=...|prevHash=...|sessionId=...|timestamp=...`. Conditional write via `attribute_not_exists(id)` for retry-safe inserts. GSI `session_id-timestamp-index` with `scanIndexForward=false` and limit 1 returns the chain tail in O(1).

**3. Deterministic mapping IDs as cache keys.** `MappingId = "MAP-" + sha256(obligationId + "#" + controlId).substring(0,16)`. The obligation-control pair becomes a reusable cache key across sessions. `MappingRepository.saveIfNotExists` catches `ConditionalCheckFailedException` for idempotent writes. The field `metadata.route` (values `llm` or `cached`) is recorded so cache hit-rate is observable.

**4. Prompt cache discipline.** Bedrock ephemeral cache via `cache_control: {type: "ephemeral"}` on every system prompt. Tool-definition JSONs are loaded byte-identically once at app startup from `src/main/resources/prompts/tools/` and reused as `JsonNode` references; re-serialising them per call would invalidate the cache prefix. `cache_creation_input_tokens` and `cache_read_input_tokens` are logged on every call.

**5. Model routing by cost shape.** Cross-region inference profiles (`eu.anthropic.*`) keep all traffic in `eu-central-1`. Haiku 4.5 for cheap structured-output stages. Amazon Nova Pro via the Converse API for batch ground-check; 50 mappings per call cuts API trips by 50x. Sonnet 4.6 for streaming chat. Opus 4.7 reserved for the heaviest reasoning paths. Fallback chain on `ThrottlingException`: Haiku to Sonnet to Nova Pro to Nova Lite. Concurrency is capped by a semaphore on `bedrock.max-concurrent`.

**6. Server-side validation of LLM JSON.** Tool-use enforces structured output. Output is parsed against an in-house shape check; malformed responses are either retried (transient) or recorded as a failed mapping in the audit chain. No malformed AI output ever reaches the proof pack.

**7. PDF bytes never transit the JVM.** `TextractAsyncService.startDocumentTextDetection` reads S3 directly. Evidence files are uploaded with required `checksumAlgorithm(SHA256)`; the backend reads the server-computed `checksumSHA256` via `HeadObject` plus `ChecksumMode.ENABLED`. The JVM never holds a regulation PDF.

**AI integrations**

**Amazon Bedrock Runtime.** Sync via `BedrockRuntimeClient` (ApacheHttpClient, 8 retries, 600s API timeout, 540s attempt timeout). Async via `BedrockRuntimeAsyncClient` (Netty) for streaming chat.

**Amazon Bedrock Agent Runtime.** KB retrieval (`Retrieve` and `RetrieveAndGenerate`) over three Knowledge Bases.

**Bedrock Knowledge Bases on S3 Vectors.** 1024-dim float32 cosine, Titan Embeddings v2. Three KBs (regulations, policies, controls) created via `aws bedrock-agent create-knowledge-base` wrapped in `null_resource` because the Terraform AWS provider has no native resource for them yet.

**Tool-use** for every structured-output stage. Tool JSONs in `src/main/resources/prompts/tools/`: `extract_obligations`, `extract_controls`, `match_obligation_to_controls`, `score_gap`, `batch_ground_check`, `extract_counterparties_from_brief`.

**Streaming with the Citations API** on the chat surface: the `chat_citations` SSE event ships before `chat_delta`, so the Sources block paints before the answer text.

**Amazon Textract** async (`StartDocumentTextDetection` plus polling) for PDF OCR.

**Amazon Transcribe** async (`StartTranscriptionJob` plus polling) for audio briefs.

**Backend stack**

**Java 25** with virtual threads enabled (`spring.threads.virtual.enabled=true`).

**Spring Boot 4.0.5** with Jackson 3 (`tools.jackson.databind.*`), Lombok everywhere, Spring Security with a custom `BearerTokenAuthFilter`, Spring WebFlux `WebClient` for sidecar HTTP.

**AWS SDK v2** (BOM 2.42.36): DynamoDB Enhanced Client (`@DynamoDbBean` with annotated getters), S3 plus S3Presigner, Bedrock Runtime sync and async, Bedrock Agent Runtime, Textract, Transcribe, Polly, Secrets Manager.

**OpenPDF 2.0.3** for proof-pack PDF generation, **Apache POI 5.3** for `mappings.xlsx` export.

**Maven** with **Jib 3.5.1** for containerless image builds (no Docker daemon required).

**Persistence**

14 DynamoDB tables on `PAY_PER_REQUEST`, all named `launchlens-*`. Highlights:

`launchlens-documents` is content-addressable. PK is the SHA-256 of file bytes. GSI `kind-last-used-at-index` for "latest documents by kind" with descending sort.

`launchlens-mappings` uses deterministic IDs, no GSI needed; lookup is direct by `id`.

`launchlens-audit-log` has GSI `session_id-timestamp-index` for chain-tail lookup in O(1).

`launchlens-jurisdiction-runs` uses composite PK (`launch_id`) plus SK (`jurisdiction_code`), GSI `jurisdiction-index` for cross-launch country views.

`launchlens-doc-jurisdictions` is denormalised: PK `jurisdiction` plus SK `document_id` for "documents applicable to country X".

Conditional writes (`attribute_not_exists(id)`) on `documents`, `mappings`, and `audit-log` for retry-safe idempotency.

**Infrastructure (Terraform)**

All in `eu-central-1`. AWS provider `~> 6.23` (required for `aws_ecs_express_gateway_service`).

**Compute.** ECS Express Mode auto-provisions an ALB plus AWS-managed TLS on `*.ecs.eu-central-1.on.aws`. Backend service `launchlens-backend-v5` at 1 vCPU and 2 GB. Sidecar service `launchlens-sidecar-v4` at 0.5 vCPU and 1 GB. JVM tuned with `-XX:InitialRAMPercentage=50.0 -XX:MaxRAMPercentage=75.0` so the JVM sizes itself to the container.

**Image build.** `null_resource.jib_build` triggers on SHA1 of `src/**` plus `pom.xml`, runs `./mvnw jib:build` against ECR with a fresh `ecr get-login-password` token. No Docker daemon, no Dockerfile.

**Storage.** Uploads bucket with versioning plus SSE-S3 plus Block Public Access plus CORS scoped to the Amplify origin. Three KB source buckets (regulations, policies, controls). One vector bucket `launchlens-vectors` for the three S3 Vectors indexes (`regulations-idx`, `policies-idx`, `controls-idx`).

**IAM.** `launchlens-task-role` scoped to `launchlens-*` DynamoDB tables, the four S3 buckets, Bedrock model invoke and retrieve, Textract / Transcribe / Polly, Secrets Manager (`launchlens/*`), CloudWatch Logs (`/ecs/launchlens-*`). Separate `launchlens-bedrock-kb-role` for KB ingestion. Sidecar task role is read-mostly with write access only to `sanctions-hits`, `audit-log`, and `evidence`.

**Secrets.** Secrets Manager holds `launchlens/sidecar-token` (32-char `random_password`) and `launchlens/opensanctions-api-key`. Both injected at task startup via the execution role.

**Observability.** `/actuator/health` aggregates per-service probes (`DynamoHealthIndicator`, `S3HealthIndicator`, `BedrockHealthIndicator`, `SidecarHealthIndicator`) for ALB target-group health checks. CloudWatch Logs at `/ecs/launchlens-backend` and `/ecs/launchlens-sidecar` with 7-day retention.

**Frontend**

React 19 plus TypeScript plus Vite plus Tailwind. Visualisation: `globe.gl` 3D jurisdiction map for the per-country verdict view, D3 force-directed graph for the compliance map (obligations, controls, mappings, gaps as nodes). PDF rendering via `react-pdf` for the proof pack and KB documents. Chat surface consumes the typed SSE event stream and renders the Sources block before the answer text.

**Scope honesty**

17 hours, four people. The architecture is sound. Known rough edges I am not pretending away.

**Thin tests.** One Spring context-loads test plus a small `JurisdictionInference` regex test. Service, repository, and controller tests are not yet written.

**Hackathon-grade Terraform.** Local state file. Default-VPC reuse. `s3vectors:*` and Bedrock foundation-model wildcards in IAM (the AWS provider does not yet model resource-level IAM for either).

**v4 document-coverage architecture is partial.** `launchlens-doc-jurisdictions` exists and is populated, but `AutoDocService.forJurisdiction` may still fall back to filename-based selection on some code paths.

**Mock API mode is specced but not wired.** `MOCK_API.md` describes the contract; the toggle is not in code yet.

**What I would do next, in priority order**

First, integration tests against LocalStack-backed DynamoDB and recorded Bedrock fixtures.

Second, migrate Terraform state to S3 plus DynamoDB lock.

Third, finish v4 coverage so every Document write fans out to `doc-jurisdictions` and `AutoDocService.forJurisdiction` becomes deterministic.

Fourth, replace the demo admin token with proper Cognito-backed auth.

Fifth, lock down the sidecar security group (currently auto-managed and wide).

**Links**

Repo: `<github link>`

Demo video: `<youtube or drive link>`

Architecture deep-dive: `docs/prism.md` in the repo
