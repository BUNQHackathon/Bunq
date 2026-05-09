package com.bunq.javabackend.repository;

import com.bunq.javabackend.model.audit.AuditChainTail;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

import java.util.Optional;

@Repository
public class AuditChainTailRepository {

    private final DynamoDbTable<AuditChainTail> table;

    public AuditChainTailRepository(
            DynamoDbEnhancedClient client,
            @Value("${aws.dynamodb.audit-chain-tails-table}") String tableName) {
        this.table = client.table(tableName, TableSchema.fromBean(AuditChainTail.class));
    }

    public Optional<AuditChainTail> findBySessionId(String sessionId) {
        return Optional.ofNullable(
                table.getItem(Key.builder().partitionValue(sessionId).build()));
    }

    public DynamoDbTable<AuditChainTail> getTable() {
        return table;
    }
}
