package com.bunq.javabackend.repository;

import com.bunq.javabackend.model.sanction.SanctionsEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.ScanEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.List;
import java.util.Map;
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
        Expression filter = Expression.builder()
                .expression("entity_name_normalized = :name")
                .expressionValues(Map.of(":name", AttributeValue.fromS(normalizedName)))
                .build();

        ScanEnhancedRequest request = ScanEnhancedRequest.builder()
                .filterExpression(filter)
                .limit(50)
                .build();

        return StreamSupport.stream(
                table.scan(request).items().spliterator(), false
        ).toList();
    }
}
