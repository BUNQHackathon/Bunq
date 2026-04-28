# DynamoDB Conventions

One table per entity (hackathon-grade; not single-table). Each entity has one `@DynamoDbBean` model and one repository. Schema defined in Terraform (`infra/dynamodb.tf`) and mirrored by `@DynamoDbAttribute` annotations on the model.

## Model class pattern

No `@Data`. Explicit `@Getter` on each field via `onMethod_` so we can attach DynamoDB annotations to the getter (where the Enhanced Client looks). Class-level: `@DynamoDbBean @NoArgsConstructor @Setter`. `@Builder` + `@AllArgsConstructor` when builder construction is useful.

```java
@DynamoDbBean
@NoArgsConstructor
@Setter
@Builder
@AllArgsConstructor
public class Document {

    @Getter(onMethod_ = {@DynamoDbPartitionKey, @DynamoDbAttribute("id")})
    private String id;  // SHA-256 content hash, lowercase hex

    @Getter(onMethod_ = {
        @DynamoDbAttribute("kind"),
        @DynamoDbSecondaryPartitionKey(indexNames = "kind-last-used-at-index")
    })
    private String kind;

    @Getter(onMethod_ = {
        @DynamoDbAttribute("last_used_at"),
        @DynamoDbSecondarySortKey(indexNames = "kind-last-used-at-index")
    })
    private Instant lastUsedAt;

    @Getter(onMethod_ = @DynamoDbAttribute("extracted_text"))
    private String extractedText;

    // ... filename, contentType, sizeBytes, s3Key, firstSeenAt, extractedAt,
    //     pageCount, obligationsExtracted, controlsExtracted
}
```

Keys: always `id` as partition key. GSIs use snake_case attribute names (`session_id`, `document_id`, `last_used_at`) matching the Terraform definitions.

## Tables

All names prefixed `launchlens-`. Defined in `locals.tf` + `dynamodb.tf`. `PAY_PER_REQUEST` billing.

| Table | PK | Notable attrs | GSI |
|---|---|---|---|
| `sessions` | `id` | `state`, `document_ids` (List<String>), `counterparties`, `verdict`, timestamps | — |
| `documents` | `id` (SHA-256 hex) | `kind`, `s3_key`, `extracted_text`, `extracted_at`, `page_count`, `obligations_extracted`, `controls_extracted`, `last_used_at` | `kind-last-used-at-index` |
| `obligations` | `id` | `session_id`, `document_id`, `deontic`, `subject`, `action`, `extraction_confidence` | `document-id-index` |
| `controls` | `id` | `session_id`, `document_id`, `control_type`, ... | `document-id-index` |
| `mappings` | `id` (= `MAP-<sha256(oblId#ctrlId):16>` — deterministic) | `obligation_id`, `control_id`, `mapping_confidence`, `metadata` (map incl. `route ∈ {llm,cached}`) | — |
| `gaps` | `id` | `session_id`, `gap_type`, `severity`, `likelihood`, `detectability`, `blast_radius`, `recoverability`, `residual_risk` | — |
| `sanctions-hits` | `id` | `session_id`, `match_status`, `counterparty`, `hits[]`, `screened_at` | — |
| `sanctions-entities` | `id` (`{list_source}#{list_entry_id}`) | `entity_name`, `entity_name_normalized`, `country`, `type`, `aliases` | — |
| `evidence` | `id` | `session_id`, `mapping_id`, `s3_key`, `sha256` (base64 from S3 checksum), `description`, `uploaded_at` | — |
| `audit-log` | `id` | `session_id`, `mapping_id`, `action`, `actor`, `timestamp`, `payload_json`, `prev_hash`, `entry_hash` | `session_id-timestamp-index` |
| `chat-messages` | `chat_id` (PK) + `created_at` (SK) | `role`, `content`, ... | — |

## Repository pattern

```java
@Repository
public class DocumentRepository {
    private final DynamoDbTable<Document> table;
    private final DynamoDbIndex<Document> kindIndex;

    public DocumentRepository(DynamoDbEnhancedClient client,
                              @Value("${aws.dynamodb.documents-table}") String tableName) {
        this.table = client.table(tableName, TableSchema.fromBean(Document.class));
        this.kindIndex = table.index("kind-last-used-at-index");
    }

    public Optional<Document> findById(String id) { ... }
    public List<Document> findByKind(String kind, int limit) { ... }
    public void saveIfNotExists(Document doc) { ... }  // attribute_not_exists(id)
    public void touchLastUsed(String id, Instant now) { ... }  // UpdateItem
}
```

## Patterns

### Conditional writes
Use `attribute_not_exists(id)` to prevent double-writes on deterministic IDs (mapping cache) and retry-safe inserts (audit log, document library).

```java
table.putItem(PutItemEnhancedRequest.builder(Document.class)
    .item(doc)
    .conditionExpression(Expression.builder()
        .expression("attribute_not_exists(id)")
        .build())
    .build());
```

Catch `ConditionalCheckFailedException` and treat as "already exists" — usually a no-op.

### GSI queries
`scanIndexForward(false)` for "latest first" (audit log chain tail; documents by last used).

### Deterministic IDs
- `Mapping.id = "MAP-" + sha256(obligationId + "#" + controlId).substring(0,16)` — makes mapping reuse free; no GSI needed to find "existing mapping for this pair".
- `Document.id = sha256(content)` — set server-side by S3 Additional Checksums on upload; the Java side reads it back via `HeadObject` with `ChecksumMode.ENABLED`.

## Audit log (chain of hashes)

`audit-log` is append-only and tamper-evident.

- PK `id` (UUID, unique per entry). GSI `session_id-timestamp-index` for reading a session's chain in order.
- Each row's `entry_hash` covers a canonical string of all fields including the previous row's `entry_hash`:

```
canonical = action=<..>|actor=<..>|id=<..>|mappingId=<..>|payload=<json>|prevHash=<..>|sessionId=<..>|timestamp=<..>
entry_hash = sha256(canonical)
```

`AuditLogService.append(...)` does the full read-latest → hash → conditional put. Writers in `MapObligationsControlsStage`, `GroundCheckStage`.

## Table-name config

```yaml
aws:
  dynamodb:
    sessions-table: ${AWS_DYNAMODB_SESSIONS_TABLE:launchlens-sessions}
    documents-table: ${AWS_DYNAMODB_DOCUMENTS_TABLE:launchlens-documents}
    obligations-table: launchlens-obligations
    # ... one key per table
```

Env overrides are injected by Terraform (`ecs_express.tf`) so the deployed container picks up the Terraform-generated names.
