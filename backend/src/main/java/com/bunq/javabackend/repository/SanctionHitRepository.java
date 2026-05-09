package com.bunq.javabackend.repository;

import com.bunq.javabackend.model.sanction.SanctionHit;
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
public class SanctionHitRepository {

    private final DynamoDbTable<SanctionHit> table;
    private final DynamoDbIndex<SanctionHit> sessionIdIndex;

    public SanctionHitRepository(
            DynamoDbEnhancedClient client,
            @Value("${aws.dynamodb.sanctions-hits-table}") String tableName) {
        this.table = client.table(tableName, TableSchema.fromBean(SanctionHit.class));
        this.sessionIdIndex = this.table.index("session-id-index");
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
        QueryEnhancedRequest request = QueryEnhancedRequest.builder()
                .queryConditional(QueryConditional.keyEqualTo(
                        Key.builder().partitionValue(sessionId).build()))
                .build();
        return sessionIdIndex.query(request).stream()
                .flatMap(page -> StreamSupport.stream(page.items().spliterator(), false))
                .toList();
    }
}
