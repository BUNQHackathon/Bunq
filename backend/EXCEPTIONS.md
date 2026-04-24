# Exception Handling

Two `@ControllerAdvice` classes, each covering a disjoint set of exception types.

## Exception classes

Under `exception/`. All extend `RuntimeException`.

| Class | Trigger | Mapped status |
|---|---|---|
| `SessionNotFoundException` | Session ID lookup miss | 404 |
| `MappingNotFoundException` | Mapping ID lookup miss | 404 |
| `NotFoundException` | Generic not-found (evidence, report, etc.) | 404 |
| `EntityAlreadyExistsException` | Conditional write failure when the row shouldn't exist | 409 |
| `SidecarCommunicationException` | `SidecarClient` cannot reach Python FastAPI | 502 (unless caught in-stage; sanctions stage catches it and continues degraded) |

## Advice 1 — legacy types

`controller/common/ErrorController.java`

Handles the custom exceptions above plus `IllegalArgumentException` (400), `HttpMessageNotReadableException` (400, malformed body), and `RuntimeException` (500, last resort).

## Advice 2 — validation + state

`web/GlobalExceptionHandler.java`

| Handler | Status | Purpose |
|---|---|---|
| `MethodArgumentNotValidException` | 400 | `@Valid` DTO failure — body includes `{correlationId, validation_errors[{field, message}]}` |
| `IllegalStateException` | 409 | `SessionService.updateState` transition-guard rejects an illegal move (e.g. `/pipeline/start` on a `COMPLETE` session) |

## Response shape

```json
{"message": "Session not found: sess-abc", "correlationId": "req-456"}
```

Validation errors return an array:
```json
{
  "correlationId": "req-456",
  "validation_errors": [
    {"field": "filename", "message": "must not be blank"},
    {"field": "kind", "message": "must not be blank"}
  ]
}
```

`correlationId` comes from MDC, injected by a request filter so every log line + error response carries the same ID.
