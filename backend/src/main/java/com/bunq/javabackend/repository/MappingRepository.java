package com.bunq.javabackend.repository;

import com.bunq.javabackend.model.mapping.Mapping;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;

@Repository
public class MappingRepository {

    private final DynamoDbTable<Mapping> table;
    private final DynamoDbIndex<Mapping> sessionIdIndex;

    public MappingRepository(
            DynamoDbEnhancedClient client,
            @Value("${aws.dynamodb.mappings-table}") String tableName) {
        this.table = client.table(tableName, TableSchema.fromBean(Mapping.class));
        this.sessionIdIndex = this.table.index("session-id-index");
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
        QueryEnhancedRequest request = QueryEnhancedRequest.builder()
                .queryConditional(QueryConditional.keyEqualTo(
                        Key.builder().partitionValue(sessionId).build()))
                .build();
        return sessionIdIndex.query(request).stream()
                .flatMap(page -> StreamSupport.stream(page.items().spliterator(), false))
                .toList();
    }
}
