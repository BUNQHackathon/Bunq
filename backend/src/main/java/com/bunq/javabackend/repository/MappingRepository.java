package com.bunq.javabackend.repository;

import com.bunq.javabackend.model.mapping.Mapping;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.ScanEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;

@Repository
public class MappingRepository {

    private final DynamoDbTable<Mapping> table;

    public MappingRepository(
            DynamoDbEnhancedClient client,
            @Value("${aws.dynamodb.mappings-table}") String tableName) {
        this.table = client.table(tableName, TableSchema.fromBean(Mapping.class));
    }

    public void save(Mapping mapping) {
        table.putItem(mapping);
    }

    public void saveIfNotExists(Mapping mapping) {
        try {
            PutItemEnhancedRequest<Mapping> req = PutItemEnhancedRequest.builder(Mapping.class)
                    .item(mapping)
                    .conditionExpression(Expression.builder()
                            .expression("attribute_not_exists(id)").build())
                    .build();
            table.putItem(req);
        } catch (ConditionalCheckFailedException ignored) {
            // already exists — no-op
        }
    }

    public Optional<Mapping> findById(String id) {
        return Optional.ofNullable(table.getItem(Key.builder().partitionValue(id).build()));
    }

    public void deleteById(String id) {
        table.deleteItem(Key.builder().partitionValue(id).build());
    }

    public List<Mapping> findBySessionId(String sessionId) {
        ScanEnhancedRequest request = ScanEnhancedRequest.builder()
                .filterExpression(software.amazon.awssdk.enhanced.dynamodb.Expression.builder()
                        .expression("session_id = :sid")
                        .expressionValues(Map.of(":sid", AttributeValue.builder().s(sessionId).build()))
                        .build())
                .build();
        return StreamSupport.stream(table.scan(request).items().spliterator(), false).toList();
    }
}
