package com.bunq.javabackend.repository;

import com.bunq.javabackend.model.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.BatchGetItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.BatchGetResultPageIterable;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.ReadBatch;
import software.amazon.awssdk.enhanced.dynamodb.model.UpdateItemEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;

@Repository
public class DocumentRepository {

    private final DynamoDbEnhancedClient enhancedClient;
    private final DynamoDbTable<Document> table;

    public DocumentRepository(
            DynamoDbEnhancedClient client,
            @Value("${aws.dynamodb.documents-table}") String tableName) {
        this.enhancedClient = client;
        this.table = client.table(tableName, TableSchema.fromBean(Document.class));
    }

    public void save(Document doc) {
        table.putItem(doc);
    }

    public void saveIfNotExists(Document doc) {
        PutItemEnhancedRequest<Document> req = PutItemEnhancedRequest.builder(Document.class)
                .item(doc)
                .conditionExpression(Expression.builder()
                        .expression("attribute_not_exists(id)").build())
                .build();
        table.putItem(req);
    }

    public Optional<Document> findById(String id) {
        return Optional.ofNullable(table.getItem(Key.builder().partitionValue(id).build()));
    }

    public List<Document> findByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();

        ReadBatch.Builder<Document> batchBuilder = ReadBatch.builder(Document.class)
                .mappedTableResource(table);
        for (String id : ids) {
            batchBuilder.addGetItem(Key.builder().partitionValue(id).build());
        }

        BatchGetResultPageIterable response = enhancedClient.batchGetItem(
                BatchGetItemEnhancedRequest.builder()
                        .readBatches(batchBuilder.build())
                        .build());

        List<Document> results = new ArrayList<>();
        response.resultsForTable(table).forEach(results::add);
        return results;
    }

    public List<Document> findByKind(String kind, int limit) {
        QueryEnhancedRequest request = QueryEnhancedRequest.builder()
                .queryConditional(QueryConditional.keyEqualTo(
                        Key.builder().partitionValue(kind).build()))
                .scanIndexForward(false)
                .limit(limit)
                .build();
        return table.index("kind-last-used-at-index").query(request).stream()
                .flatMap(page -> StreamSupport.stream(page.items().spliterator(), false))
                .toList();
    }

    public List<Document> scanAll(int limit) {
        return StreamSupport.stream(table.scan().items().spliterator(), false)
                .limit(limit)
                .toList();
    }

    public void touchLastUsed(String id, Instant now) {
        Document update = Document.builder()
                .id(id)
                .lastUsedAt(now)
                .build();
        table.updateItem(UpdateItemEnhancedRequest.builder(Document.class)
                .item(update)
                .ignoreNulls(true)
                .build());
    }
}
