# Prompt Cache (Bedrock)

## How it works

Cache the large regulation corpus as a `cache_control: {type: "ephemeral"}` block in the system prompt. First call of a session is a cache write (~2× cost). Subsequent calls within 5 min read from cache (~0.1× input cost).

## Cache behaviour

| Call | `cache_creation_input_tokens` | `cache_read_input_tokens` |
|---|---|---|
| First call of session | `> 0` | `0` |
| Subsequent within 5 min | `0` | `> 0` |
| After TTL expiry | `> 0` | `0` |

Log both fields on every Bedrock call.

## What invalidates the cache

- Tool schema JSON changes (field order, new field, removed field)
- System prompt changes
- `cache_control` block moves position
- TTL (5 min default) expires

## Tool definitions

Serialise tool definitions **once at application startup** and reuse the identical byte sequence on every Bedrock call. Store them as a `@Bean` or `static final` String — do not re-serialise per call.

```java
public static final String TOOL_DEFINITIONS = """
    [{"name": "extract_obligations", "description": "...", "input_schema": {...}}, ...]
    """;
```

Any change to the tool JSON invalidates all cached prefixes.

## Request body pattern

```json
{
  "anthropic_version": "bedrock-2023-05-31",
  "max_tokens": 4096,
  "system": [
    {"type": "text", "text": "...", "cache_control": {"type": "ephemeral"}}
  ],
  "messages": [...],
  "tools": <TOOL_DEFINITIONS>  // must be byte-identical across calls
}
```

## Debugging checklist

1. Log `cache_creation_input_tokens` + `cache_read_input_tokens` on every Bedrock response
2. First call of session: expect `cache_creation > 0`, `cache_read == 0`
3. Second call within TTL: expect `cache_creation == 0`, `cache_read > 0`
4. If `cache_read` stays 0 on repeated calls: check that tool definitions serialisation is byte-identical across calls (same field order, no whitespace variance)
