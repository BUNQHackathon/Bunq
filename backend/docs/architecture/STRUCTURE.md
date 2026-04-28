# Directory Structure

Package base: `com.bunq.javabackend`. Generated with focus on the shapes that matter for future contributors — not an exhaustive file dump.

```
src/main/java/com/bunq/javabackend/
├── JavabackendApplication.java
│
├── config/
│   ├── AwsConfig.java                ← S3, DynamoDB, Bedrock (sync+async), Textract, Transcribe beans
│   ├── DynamoDbConfig.java           ← DynamoDbTable<T> beans (one per entity)
│   ├── CorsConfig.java               ← reads cors.allowed-origins from application.yaml
│   ├── SecurityConfig.java           ← permissive passthrough (no Spring Security on public endpoints)
│   └── health/                       ← actuator HealthIndicators
│       ├── SidecarHealthIndicator.java
│       ├── DynamoHealthIndicator.java
│       ├── S3HealthIndicator.java
│       └── BedrockHealthIndicator.java
│
├── controller/
│   ├── SessionController.java            ← POST /sessions, GET /sessions/{id}
│   ├── DocumentsController.java          ← user document library (presign, finalize, list, get)
│   ├── SessionDocumentsController.java   ← attach / detach Document ↔ Session
│   ├── KbRegulationsController.java      ← Bedrock KB regulation corpus browser
│   ├── EvidenceController.java           ← evidence presign/finalize + proof-tree + compliance-map
│   ├── ReportController.java             ← GET /sessions/{id}/report.pdf (302 presigned)
│   ├── PipelineController.java           ← POST /pipeline/start, SSE /events
│   ├── ControlController.java
│   ├── ObligationController.java
│   ├── MappingController.java
│   ├── GapController.java
│   ├── SanctionsController.java
│   ├── ChatController.java               ← streaming chat grounded in the 3 KBs
│   ├── RagController.java                ← /query + /query/stream
│   ├── GraphController.java
│   ├── FilesController.java              ← generic presigned-url helper
│   ├── JurisdictionsController.java
│   └── common/
│       └── ErrorController.java          ← @ControllerAdvice for legacy exception types
│
├── web/
│   └── GlobalExceptionHandler.java       ← @ControllerAdvice for @Valid (400) + IllegalStateException (409)
│
├── client/
│   └── SidecarClient.java                ← WebClient to Python sidecar
│
├── service/
│   ├── SessionService.java                ← with state-transition guards
│   ├── BedrockService.java                ← sync InvokeModel + cache metrics
│   ├── BedrockStreamingService.java       ← async InvokeModelWithResponseStream + cache metrics
│   ├── TextractAsyncService.java          ← Start/GetDocumentTextDetection polling (zero-heap PDF)
│   ├── TranscribeAsyncService.java        ← Start/GetTranscriptionJob polling (audio)
│   ├── EvidenceService.java               ← hashFromS3(s3Key) via HeadObject + ChecksumMode
│   ├── ReportService.java                 ← OpenPDF → S3 → presigned URL; also presignExistingReport
│   ├── AuditLogService.java               ← chained SHA-256 append
│   ├── ChatService.java                   ← Bedrock streaming for /chat
│   ├── ControlService / ObligationService / MappingService / GapService / SanctionsService
│   ├── sse/
│   │   └── SseEmitterService.java         ← send(sessionId, eventName, data) — native named events
│   └── pipeline/
│       ├── PipelineOrchestrator.java      ← runs stages sequentially, emits lifecycle SSE
│       ├── PipelineContext.java           ← session + collected results + ingestedDocuments
│       ├── PipelineStage.java             ← enum
│       ├── IngestedDocument.java          ← record {documentId, kind, text}
│       ├── prompts/                       ← SystemPrompts constants
│       ├── bedrock/                       ← ToolDefinitions (loaded from src/main/resources/prompts/tools/*.json)
│       └── stage/
│           ├── IngestStage.java           ← iterates Session.documentIds; Textract/Transcribe cache
│           ├── ExtractObligationsStage    ← findByDocumentId cache; clone-on-hit
│           ├── ExtractControlsStage       ← same pattern for policy docs
│           ├── MapObligationsControlsStage← deterministic mapping IDs + cache
│           ├── GapAnalyzeStage            ← 5-dim residual risk
│           ├── SanctionsScreenStage       ← try/catch → degraded-mode continue
│           ├── GroundCheckStage           ← verified / dropped per mapping
│           └── NarrateStage               ← summary + ReportService.generate
│
├── repository/
│   ├── SessionRepository, DocumentRepository, ObligationRepository, ControlRepository,
│   ├── MappingRepository, GapRepository, EvidenceRepository, SanctionHitRepository,
│   ├── AuditLogRepository (has saveIfNotExists + findLatestBySessionId via GSI),
│   └── ChatMessageRepository
│
├── model/                                 ← @DynamoDbBean entities
│   ├── document/Document.java             ← id = SHA-256 hex; GSI kind-last-used-at-index
│   ├── session/Session.java               ← documentIds: List<String>
│   ├── obligation/Obligation.java         ← +documentId + GSI document-id-index
│   ├── control/Control.java               ← +documentId + GSI document-id-index
│   ├── mapping/Mapping.java               ← deterministic id = MAP-<sha256(oblId#ctrlId):16>; metadata map
│   ├── gap/Gap.java                       ← +severity/likelihood/detectability/blastRadius/recoverability + residualRisk
│   ├── evidence/Evidence.java             ← sha256 from S3 Additional Checksums
│   ├── audit/AuditLogEntry.java           ← prevHash + entryHash chain; GSI session_id-timestamp-index
│   ├── sanction/                          ← SanctionHit, SanctionMatch, Counterparty
│   ├── chat/ChatMessage.java
│   └── enums/
│       ├── SessionState, MappingType, GapType, GapStatus
│       └── BedrockModel.java              ← eu.anthropic.* inference profile IDs (Opus/Sonnet/Haiku)
│
├── dto/
│   ├── request/                           ← @Valid + @NotBlank/@NotNull/@Size; includes DocumentPresignRequest, DocumentFinalizeRequest, Evidence*, chat, pipeline
│   ├── response/                          ← Document*DTO, SessionResponseDTO, ObligationResponseDTO, ... , events/* (SSE payloads)
│   └── response/sidecar/                  ← GraphDAG, GraphNode, GraphEdge
│
├── helper/
│   ├── S3PresignHelper.java               ← presignEvidencePut, presignDocumentUpload — both carry SHA-256 checksum
│   ├── IdGenerator.java
│   └── mapper/                            ← static Model↔DTO converters
│
└── exception/
    ├── SessionNotFoundException, MappingNotFoundException, NotFoundException
    ├── EntityAlreadyExistsException
    └── SidecarCommunicationException
```

## Naming

| Element | Convention | Example |
|---|---|---|
| Controller | `{Resource}Controller` | `DocumentsController` |
| Service | `{Resource}Service` | `EvidenceService` |
| Repository | `{Resource}Repository` | `DocumentRepository` |
| Pipeline stage | `{Verb}{Resource}Stage` | `ExtractObligationsStage` |
| DynamoDB model | PascalCase singular | `Document`, `Obligation` |
| DynamoDB table | `launchlens-<plural-kebab>` | `launchlens-documents`, `launchlens-audit-log` |
| DynamoDB PK | `id` (String) | `document.id = SHA-256 hex` |
| GSI | `<partition>-<sort>-index` (or just `<partition>-index` for hash-only) | `document-id-index`, `kind-last-used-at-index`, `session_id-timestamp-index` |
| SSE event name | `resource.event` (lowercase, dot-separated) | `obligation.extracted`, `document.cached` |
