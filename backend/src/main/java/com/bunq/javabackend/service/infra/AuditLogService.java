package com.bunq.javabackend.service.infra;

import com.bunq.javabackend.model.audit.AuditChainTail;
import com.bunq.javabackend.model.audit.AuditLogEntry;
import com.bunq.javabackend.repository.AuditChainTailRepository;
import com.bunq.javabackend.repository.AuditLogRepository;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.model.ConditionCheck;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactPutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactWriteItemsEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.UpdateItemEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditLogService {

    private static final int MAX_RETRIES = 5;
    private static final long[] BACKOFF_MS = {50, 100, 200, 400, 800};

    private final AuditLogRepository repo;
    private final AuditChainTailRepository chainTailRepo;
    private final DynamoDbEnhancedClient enhancedClient;
    private final ObjectMapper mapper;

    public AuditLogEntry append(String sessionId, String mappingId, String action,
                                String actor, Map<String, Object> payload) throws Exception {
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                Thread.sleep(BACKOFF_MS[attempt - 1]);
                log.debug("Retrying audit append for session {} (attempt {})", sessionId, attempt + 1);
            }

            Optional<AuditChainTail> currentTail = chainTailRepo.findBySessionId(sessionId);
            String prevHash = currentTail.map(AuditChainTail::getTailHash).orElse("GENESIS");

            String payloadJson = mapper.writeValueAsString(payload == null ? Map.of() : payload);
            Instant now = Instant.now();
            String id = UUID.randomUUID().toString();

            String canonical = "action=" + nullSafe(action)
                    + "|actor=" + nullSafe(actor)
                    + "|id=" + id
                    + "|mappingId=" + nullSafe(mappingId)
                    + "|payload=" + payloadJson
                    + "|prevHash=" + prevHash
                    + "|sessionId=" + sessionId
                    + "|timestamp=" + now;
            String entryHash = sha256Hex(canonical);

            AuditLogEntry entry = AuditLogEntry.builder()
                    .id(id).sessionId(sessionId).mappingId(mappingId)
                    .action(action).actor(actor).timestamp(now)
                    .payloadJson(payloadJson).prevHash(prevHash).entryHash(entryHash)
                    .build();

            AuditChainTail newTail = AuditChainTail.builder()
                    .sessionId(sessionId)
                    .tailHash(entryHash)
                    .tailEntryId(id)
                    .updatedAt(now)
                    .build();

            // Condition on the tail record: either it doesn't exist yet (new session)
            // or the current tail_hash matches what we read (no concurrent writer won).
            Expression tailCondition;
            if (currentTail.isEmpty()) {
                tailCondition = Expression.builder()
                        .expression("attribute_not_exists(session_id)")
                        .build();
            } else {
                tailCondition = Expression.builder()
                        .expression("tail_hash = :expectedPrev")
                        .expressionValues(Map.of(
                                ":expectedPrev", AttributeValue.fromS(prevHash)))
                        .build();
            }

            TransactPutItemEnhancedRequest<AuditLogEntry> entryPut =
                    TransactPutItemEnhancedRequest.builder(AuditLogEntry.class)
                            .item(entry)
                            .conditionExpression(Expression.builder()
                                    .expression("attribute_not_exists(id)")
                                    .build())
                            .build();

            TransactPutItemEnhancedRequest<AuditChainTail> tailPut =
                    TransactPutItemEnhancedRequest.builder(AuditChainTail.class)
                            .item(newTail)
                            .conditionExpression(tailCondition)
                            .build();

            TransactWriteItemsEnhancedRequest txRequest =
                    TransactWriteItemsEnhancedRequest.builder()
                            .addPutItem(repo.getTable(), entryPut)
                            .addPutItem(chainTailRepo.getTable(), tailPut)
                            .build();

            try {
                enhancedClient.transactWriteItems(txRequest);
                return entry;
            } catch (TransactionCanceledException ex) {
                if (isRetryableTransactionFailure(ex) && attempt < MAX_RETRIES) {
                    log.debug("Retryable transaction failure on audit append for session {}, attempt {}, will retry", sessionId, attempt + 1);
                    continue;
                }
                throw new RuntimeException(
                        "Failed to append audit entry after " + (attempt + 1) + " attempts for session " + sessionId, ex);
            }
        }

        throw new RuntimeException("Failed to append audit entry after retries for session " + sessionId);
    }

    /**
     * Returns true if the transaction was cancelled exclusively because of
     * retryable reasons: ConditionalCheckFailed (optimistic-lock miss on the
     * chain-tail row) or TransactionConflict (concurrent TransactWriteItems
     * targeting the same item). Any other cancel reason
     * (e.g. ItemCollectionSizeLimitExceeded, ProvisionedThroughputExceeded,
     * ThrottlingError, ValidationError) is not safe to retry blindly and is
     * propagated to the caller.
     */
    private boolean isRetryableTransactionFailure(TransactionCanceledException ex) {
        List<CancellationReason> reasons = ex.cancellationReasons();
        if (reasons == null || reasons.isEmpty()) {
            return false;
        }
        boolean anyRetryable = false;
        for (CancellationReason reason : reasons) {
            String code = reason.code();
            if (code == null || "None".equals(code)) {
                continue;
            }
            if ("ConditionalCheckFailed".equals(code) || "TransactionConflict".equals(code)) {
                anyRetryable = true;
            } else {
                // Unknown / non-retryable failure — propagate to caller
                return false;
            }
        }
        return anyRetryable;
    }

    private String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private String sha256Hex(String input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
