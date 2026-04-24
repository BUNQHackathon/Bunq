package com.bunq.javabackend.repository;

import com.bunq.javabackend.model.control.Control;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.ScanEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;

@Repository
public class ControlRepository {

    private final DynamoDbTable<Control> table;

    public ControlRepository(
            DynamoDbEnhancedClient client,
            @Value("${aws.dynamodb.controls-table}") String tableName) {
        this.table = client.table(tableName, TableSchema.fromBean(Control.class));
    }

    public void save(Control control) {
        table.putItem(control);
    }

    public Optional<Control> findById(String id) {
        return Optional.ofNullable(table.getItem(Key.builder().partitionValue(id).build()));
    }

    public void deleteById(String id) {
        table.deleteItem(Key.builder().partitionValue(id).build());
    }

    public List<Control> findBySessionId(String sessionId) {
        ScanEnhancedRequest request = ScanEnhancedRequest.builder()
                .filterExpression(software.amazon.awssdk.enhanced.dynamodb.Expression.builder()
                        .expression("session_id = :sid")
                        .expressionValues(Map.of(":sid", AttributeValue.builder().s(sessionId).build()))
                        .build())
                .build();
        return StreamSupport.stream(table.scan(request).items().spliterator(), false).toList();
    }

    public List<Control> scanAll(int limit) {
        return StreamSupport.stream(table.scan().items().spliterator(), false)
                .limit(limit)
                .toList();
    }

    public List<Control> findByDocumentId(String documentId) {
        QueryEnhancedRequest request = QueryEnhancedRequest.builder()
                .queryConditional(QueryConditional.keyEqualTo(
                        Key.builder().partitionValue(documentId).build()))
                .build();
        return table.index("document-id-index").query(request).stream()
                .flatMap(page -> StreamSupport.stream(page.items().spliterator(), false))
                .toList();
    }
}
