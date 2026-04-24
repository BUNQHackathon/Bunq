package com.bunq.javabackend.repository;

import com.bunq.javabackend.model.evidence.Evidence;
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
public class EvidenceRepository {

    private final DynamoDbTable<Evidence> table;

    public EvidenceRepository(
            DynamoDbEnhancedClient client,
            @Value("${aws.dynamodb.evidence-table}") String tableName) {
        this.table = client.table(tableName, TableSchema.fromBean(Evidence.class));
    }

    public void save(Evidence evidence) {
        table.putItem(evidence);
    }

    public Optional<Evidence> findById(String id) {
        return Optional.ofNullable(table.getItem(Key.builder().partitionValue(id).build()));
    }

    public void deleteById(String id) {
        table.deleteItem(Key.builder().partitionValue(id).build());
    }

    public List<Evidence> findBySessionId(String sessionId) {
        ScanEnhancedRequest request = ScanEnhancedRequest.builder()
                .filterExpression(software.amazon.awssdk.enhanced.dynamodb.Expression.builder()
                        .expression("session_id = :sid")
                        .expressionValues(Map.of(":sid", AttributeValue.builder().s(sessionId).build()))
                        .build())
                .build();
        return StreamSupport.stream(table.scan(request).items().spliterator(), false).toList();
    }
}
