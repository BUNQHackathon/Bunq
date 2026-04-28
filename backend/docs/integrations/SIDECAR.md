# Sidecar Communication

Java calls the sidecar via `WebClient` (Spring `WebClient.Builder`) with the sidecar's ECS Express endpoint injected from config as `sidecar.base-url` (env var `SIDECAR_BASE_URL`). Never call from a controller — always wrap in a service method.

## Endpoints the Java side calls

| Method | Path | Java client method | Returns |
|---|---|---|---|
| POST | `/sanctions/screen` | `SidecarClient.screenSanctions(sessionId, counterparties, briefText)` | `SanctionHit[]` |
| GET | `/proof-tree/{mappingId}` | `SidecarClient.getProofTree(mappingId)` | `GraphDAG` |
| GET | `/compliance-map/{sessionId}` | `SidecarClient.getComplianceMap(sessionId)` | `GraphDAG` |
| GET | `/health` | `SidecarClient.health()` | 200 OK (body ignored; used by `SidecarHealthIndicator`) |

## Evidence hashing — not via sidecar

Evidence SHA-256 now comes from S3 Additional Checksums: presigned PUT URLs are signed with `checksumAlgorithm(SHA256)`, so S3 computes and stores the digest server-side. Read it back in `EvidenceService.hashFromS3(s3Key)` via `HeadObject` + `ChecksumMode.ENABLED`. Zero bytes through the JVM. The sidecar's `/evidence/hash` endpoint still exists in Python for independent use but Java does not call it.

## Ingest OCR — not via sidecar

Textract async (`StartDocumentTextDetection`) is called directly from `TextractAsyncService` — no sidecar intermediary. See `IngestStage`.

## Error handling

Sidecar call failures are wrapped in `SidecarCommunicationException` (extends `RuntimeException`). `ErrorController` catches it and returns `502 Bad Gateway` to the client.

## Contract

All responses are structured JSON. Java deserialises with a DTO mapper. Pin the expected response shape in a comment above each WebClient call — if the sidecar contract changes, the Java side must be updated in lockstep.

## Security

The sidecar is internal only. SecurityConfig blocks any direct public access. Java communicates with it via the internal App Runner service URL.