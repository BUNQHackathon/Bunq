package com.bunq.javabackend.repository;

import com.bunq.javabackend.model.sanction.SanctionsEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

import java.util.List;
import java.util.stream.StreamSupport;

@Repository
public class SanctionsEntityRepository {

    private final DynamoDbTable<SanctionsEntity> table;

    public SanctionsEntityRepository(
            DynamoDbEnhancedClient client,
            @Value("${aws.dynamodb.sanctions-entities-table}") String tableName) {
        this.table = client.table(tableName, TableSchema.fromBean(SanctionsEntity.class));
    }

    public List<SanctionsEntity> findByNormalizedName(String normalizedName) {
        QueryEnhancedRequest request = QueryEnhancedRequest.builder()
                .queryConditional(QueryConditional.keyEqualTo(
                        Key.builder().partitionValue(normalizedName).build()))
                .build();
        return table.index("entity-name-normalized-index").query(request).stream()
                .flatMap(page -> StreamSupport.stream(page.items().spliterator(), false))
                .toList();
    }
}
