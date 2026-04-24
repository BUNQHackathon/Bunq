package com.bunq.javabackend.repository;

import com.bunq.javabackend.model.session.Session;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;

@Repository
public class SessionRepository {

    private final DynamoDbTable<Session> table;

    public SessionRepository(
            DynamoDbEnhancedClient client,
            @Value("${aws.dynamodb.sessions-table}") String tableName) {
        this.table = client.table(tableName, TableSchema.fromBean(Session.class));
    }

    public void save(Session session) {
        table.putItem(session);
    }

    public Optional<Session> findById(String id) {
        return Optional.ofNullable(table.getItem(Key.builder().partitionValue(id).build()));
    }

    public void deleteById(String id) {
        table.deleteItem(Key.builder().partitionValue(id).build());
    }

    public boolean existsById(String id) {
        return findById(id).isPresent();
    }

    public List<Session> scanAll() {
        return StreamSupport.stream(table.scan().items().spliterator(), false)
                .toList();
    }

    public List<Session> scanAll(int limit) {
        return StreamSupport.stream(table.scan().items().spliterator(), false)
                .limit(limit)
                .toList();
    }
}
