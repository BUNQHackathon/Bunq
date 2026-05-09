package com.bunq.javabackend.repository;

import com.bunq.javabackend.model.audit.AuditLogEntry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;

@Repository
public class AuditLogRepository {

    private final DynamoDbTable<AuditLogEntry> table;
    private final DynamoDbIndex<AuditLogEntry> sessionIdIndex;

    public AuditLogRepository(
            DynamoDbEnhancedClient client,
            @Value("${aws.dynamodb.audit-log-table}") String tableName) {
        this.table = client.table(tableName, TableSchema.fromBean(AuditLogEntry.class));
        this.sessionIdIndex = this.table.index("session_id-timestamp-index");
    }

    public void save(AuditLogEntry entry) {
        table.putItem(entry);
    }

    public Optional<AuditLogEntry> findById(String id) {
        return Optional.ofNullable(table.getItem(Key.builder().partitionValue(id).build()));
    }

    public void deleteById(String id) {
        table.deleteItem(Key.builder().partitionValue(id).build());
    }

    public List<AuditLogEntry> findBySessionId(String sessionId) {
        QueryEnhancedRequest request = QueryEnhancedRequest.builder()
                .queryConditional(QueryConditional.keyEqualTo(
                        Key.builder().partitionValue(sessionId).build()))
                .build();
        return sessionIdIndex.query(request).stream()
                .flatMap(page -> StreamSupport.stream(page.items().spliterator(), false))
                .toList();
    }

    public Optional<AuditLogEntry> findLatestBySessionId(String sessionId) {
        QueryEnhancedRequest q = QueryEnhancedRequest.builder()
                .queryConditional(QueryConditional.keyEqualTo(
                        Key.builder().partitionValue(sessionId).build()))
                .scanIndexForward(false)
                .limit(1)
                .build();
        return sessionIdIndex.query(q).stream()
                .findFirst().flatMap(p -> p.items().stream().findFirst());
    }

    public void saveIfNotExists(AuditLogEntry entry) {
        PutItemEnhancedRequest<AuditLogEntry> req = PutItemEnhancedRequest.builder(AuditLogEntry.class)
                .item(entry)
                .conditionExpression(Expression.builder()
                        .expression("attribute_not_exists(id)").build())
                .build();
        table.putItem(req);
    }

    public DynamoDbTable<AuditLogEntry> getTable() {
        return table;
    }
}
