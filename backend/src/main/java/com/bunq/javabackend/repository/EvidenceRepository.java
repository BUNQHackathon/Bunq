package com.bunq.javabackend.repository;

import com.bunq.javabackend.model.evidence.Evidence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;

@Repository
public class EvidenceRepository {

    private final DynamoDbTable<Evidence> table;
    private final DynamoDbIndex<Evidence> sessionIdIndex;

    public EvidenceRepository(
            DynamoDbEnhancedClient client,
            @Value("${aws.dynamodb.evidence-table}") String tableName) {
        this.table = client.table(tableName, TableSchema.fromBean(Evidence.class));
        this.sessionIdIndex = this.table.index("session-id-index");
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
        QueryEnhancedRequest request = QueryEnhancedRequest.builder()
                .queryConditional(QueryConditional.keyEqualTo(
                        Key.builder().partitionValue(sessionId).build()))
                .build();
        return sessionIdIndex.query(request).stream()
                .flatMap(page -> StreamSupport.stream(page.items().spliterator(), false))
                .toList();
    }
}
