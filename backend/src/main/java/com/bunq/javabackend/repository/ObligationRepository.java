package com.bunq.javabackend.repository;

import com.bunq.javabackend.model.obligation.Obligation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;

@Repository
public class ObligationRepository {

    private final DynamoDbTable<Obligation> table;

    public ObligationRepository(
            DynamoDbEnhancedClient client,
            @Value("${aws.dynamodb.obligations-table}") String tableName) {
        this.table = client.table(tableName, TableSchema.fromBean(Obligation.class));
    }

    public void save(Obligation obligation) {
        table.putItem(obligation);
    }

    public Optional<Obligation> findById(String id) {
        return Optional.ofNullable(table.getItem(Key.builder().partitionValue(id).build()));
    }

    public void deleteById(String id) {
        table.deleteItem(Key.builder().partitionValue(id).build());
    }

    public List<Obligation> findBySessionId(String sessionId) {
        QueryEnhancedRequest request = QueryEnhancedRequest.builder()
                .queryConditional(QueryConditional.keyEqualTo(
                        Key.builder().partitionValue(sessionId).build()))
                .build();
        return table.index("session-id-index").query(request).stream()
                .flatMap(page -> StreamSupport.stream(page.items().spliterator(), false))
                .toList();
    }

    public List<Obligation> scanAll(int limit) {
        return StreamSupport.stream(table.scan().items().spliterator(), false)
                .limit(limit)
                .toList();
    }

    public List<Obligation> findByDocumentId(String documentId) {
        QueryEnhancedRequest request = QueryEnhancedRequest.builder()
                .queryConditional(QueryConditional.keyEqualTo(
                        Key.builder().partitionValue(documentId).build()))
                .build();
        return table.index("document-id-index").query(request).stream()
                .flatMap(page -> StreamSupport.stream(page.items().spliterator(), false))
                .toList();
    }
}
