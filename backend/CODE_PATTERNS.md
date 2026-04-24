# Code Patterns

## Lombok

Used everywhere. Per layer:

**DTOs** — `@Data @Builder @NoArgsConstructor @AllArgsConstructor`.

**Services** — `@Service @RequiredArgsConstructor` (constructor injection, no field `@Autowired`). `@Slf4j` when logging.

**DynamoDB beans** — no `@Data`. Explicit `@Getter`/`@Setter` with `onMethod_ =` so DynamoDB annotations land on the getter.

```java
@DynamoDbBean @NoArgsConstructor @Setter @Builder @AllArgsConstructor
public class Obligation {
    @Getter(onMethod_ = {@DynamoDbPartitionKey, @DynamoDbAttribute("id")})
    private String id;

    @Getter(onMethod_ = @DynamoDbAttribute("session_id"))
    private String sessionId;

    @Getter(onMethod_ = {
        @DynamoDbAttribute("document_id"),
        @DynamoDbSecondaryPartitionKey(indexNames = "document-id-index")
    })
    private String documentId;

    // ...
}
```

**Exceptions** — `public class FooException extends RuntimeException` with one or two constructors. No checked exceptions.

## Jackson 3

Spring Boot 4 ships Jackson 3. Always `tools.jackson.databind.*`. Importing `com.fasterxml.jackson.*` will compile but crosses ClassLoaders and won't serialize through Spring — hard-to-debug runtime failure.

## Java idioms

- `var` for local declarations when the type is obvious.
- Switch expressions (`case X -> { ... }`).
- `Stream.toList()` instead of `.collect(Collectors.toList())`.
- Records for small immutable DTOs (e.g., `IngestedDocument`).
- No `throws` on service/controller signatures — wrap and let the advice handle.

## No comments

Default to none. Add only when WHY is non-obvious (hidden constraint, workaround, invariant). Never describe WHAT the code does.

## Presigned URLs

Generated in `helper/S3PresignHelper.java`, never inline in controllers. PUT presigns always include `.checksumAlgorithm(ChecksumAlgorithm.SHA256)` so S3 computes + stores the SHA-256 server-side.

## SSE

`SseEmitter` returned from a `@GetMapping(produces = TEXT_EVENT_STREAM_VALUE)`. Emitters managed per-session by `SseEmitterService`:

```java
sseEmitterService.send(sessionId, "obligation.extracted", obligationDto);
// wire output:
// event: obligation.extracted
// data: {"id":"OBL-...", ...}
```

Heartbeat `event: ping` every 15 s. Per-session emitter list; dead emitters are culled on send failure.

**Per-record events** (drives the live graph): `obligation.extracted`, `control.extracted`, `mapping.computed`, `gap.identified`, `sanctions.hit`, `ground_check.verified`, `ground_check.dropped`, `narrative.completed`, plus `document.extracted` / `document.cached`.

**Lifecycle events**: `stage.started/complete/skipped`, `pipeline.completed/failed`, `mapping.progress`, `ingest.polling`, `transcribe.polling`, `sanctions.degraded`.

Per-record events are emitted stage-by-stage (after each Bedrock call returns the array, the stage iterates and emits per element). Token-level streaming is not implemented.

## Async endpoints

`POST /pipeline/start` returns `202 Accepted` immediately. The pipeline runs on a background `CompletableFuture`; the client subscribes to `GET /api/v1/events?sessionId=...` for progress.

## Bedrock request shape

Tool-based extraction:
```json
{
  "anthropic_version": "bedrock-2023-05-31",
  "max_tokens": 4096,
  "system": [{"type": "text", "text": "...", "cache_control": {"type": "ephemeral"}}],
  "messages": [...],
  "tools": [ /* byte-identical across calls */ ]
}
```

Tool definitions live under `src/main/resources/prompts/tools/*.json`, loaded once at startup. Re-serializing per call breaks the cache hit; don't.
