package com.bunq.javabackend.repository;

import com.bunq.javabackend.model.launch.Launch;
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
public class LaunchRepository {

    private final DynamoDbTable<Launch> table;

    public LaunchRepository(
            DynamoDbEnhancedClient client,
            @Value("${aws.dynamodb.launches-table}") String tableName) {
        this.table = client.table(tableName, TableSchema.fromBean(Launch.class));
    }

    public Optional<Launch> findById(String id) {
        return Optional.ofNullable(table.getItem(Key.builder().partitionValue(id).build()));
    }

    public void save(Launch launch) {
        table.putItem(launch);
    }

    public List<Launch> findAll() {
        return StreamSupport.stream(table.scan().items().spliterator(), false).toList();
    }
}
