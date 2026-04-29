package com.bunq.javabackend.repository;

import com.bunq.javabackend.model.observability.SessionCost;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

import java.util.Optional;

@Repository
public class SessionCostRepository {

    private final DynamoDbTable<SessionCost> table;

    public SessionCostRepository(
            DynamoDbEnhancedClient client,
            @Value("${aws.dynamodb.session-cost-table}") String tableName) {
        this.table = client.table(tableName, TableSchema.fromBean(SessionCost.class));
    }

    public void save(SessionCost item) {
        table.putItem(item);
    }

    public Optional<SessionCost> findById(String sessionId) {
        return Optional.ofNullable(table.getItem(Key.builder().partitionValue(sessionId).build()));
    }
}
