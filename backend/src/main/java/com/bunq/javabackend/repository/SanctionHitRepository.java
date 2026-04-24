package com.bunq.javabackend.repository;

import com.bunq.javabackend.model.sanction.SanctionHit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.ScanEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;

@Repository
public class SanctionHitRepository {

    private final DynamoDbTable<SanctionHit> table;

    public SanctionHitRepository(
            DynamoDbEnhancedClient client,
            @Value("${aws.dynamodb.sanctions-hits-table}") String tableName) {
        this.table = client.table(tableName, TableSchema.fromBean(SanctionHit.class));
    }

    public void save(SanctionHit sanctionHit) {
        table.putItem(sanctionHit);
    }

    public Optional<SanctionHit> findById(String id) {
        return Optional.ofNullable(table.getItem(Key.builder().partitionValue(id).build()));
    }

    public void deleteById(String id) {
        table.deleteItem(Key.builder().partitionValue(id).build());
    }

    public List<SanctionHit> findBySessionId(String sessionId) {
        ScanEnhancedRequest request = ScanEnhancedRequest.builder()
                .filterExpression(software.amazon.awssdk.enhanced.dynamodb.Expression.builder()
                        .expression("session_id = :sid")
                        .expressionValues(Map.of(":sid", AttributeValue.builder().s(sessionId).build()))
                        .build())
                .build();
        return StreamSupport.stream(table.scan(request).items().spliterator(), false).toList();
    }
}
