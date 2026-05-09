package com.bunq.javabackend.repository;

import com.bunq.javabackend.model.session.Session;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;

@Repository
public class SessionRepository {

    private final DynamoDbClient dynamoDbClient;
    private final DynamoDbTable<Session> table;
    private final String tableName;

    public SessionRepository(
            DynamoDbClient dynamoDbClient,
            DynamoDbEnhancedClient client,
            @Value("${aws.dynamodb.sessions-table}") String tableName) {
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName;
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

    public List<Session> findByLaunchId(String launchId) {
        var index = table.index("launch-sessions-index");
        var qc = QueryConditional.keyEqualTo(Key.builder().partitionValue(launchId).build());
        return index.query(r -> r.queryConditional(qc))
                .stream()
                .flatMap(p -> p.items().stream())
                .toList();
    }

    /**
     * Atomically transitions session state from expectedState to newState.
     * If expectedState is null, the condition checks that the item exists and state is absent.
     * Throws ConditionalCheckFailedException if the current state does not match.
     */
    public void updateStateConditional(String sessionId, String expectedState, String newState) {
        UpdateItemRequest.Builder req = UpdateItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("id", AttributeValue.fromS(sessionId)))
                .updateExpression("SET #st = :newState, updatedAt = :ts")
                .expressionAttributeNames(Map.of("#st", "state"));

        if (expectedState != null) {
            req.conditionExpression("attribute_exists(id) AND #st = :expectedState")
               .expressionAttributeValues(Map.of(
                       ":newState", AttributeValue.fromS(newState),
                       ":expectedState", AttributeValue.fromS(expectedState),
                       ":ts", AttributeValue.fromS(Instant.now().toString())
               ));
        } else {
            req.conditionExpression("attribute_exists(id) AND attribute_not_exists(#st)")
               .expressionAttributeValues(Map.of(
                       ":newState", AttributeValue.fromS(newState),
                       ":ts", AttributeValue.fromS(Instant.now().toString())
               ));
        }

        dynamoDbClient.updateItem(req.build());
    }

    /**
     * Atomically appends stageName to the completed_stages list if not already present.
     * On ConditionalCheckFailedException caused by a duplicate stage, this is a no-op.
     * If the session is missing, the original exception is rethrown.
     */
    public void addCompletedStage(String sessionId, String stageName) {
        try {
            dynamoDbClient.updateItem(UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of("id", AttributeValue.fromS(sessionId)))
                    .updateExpression(
                            "SET completed_stages = list_append(if_not_exists(completed_stages, :empty), :stage)")
                    .conditionExpression(
                            "attribute_exists(id) AND (attribute_not_exists(completed_stages) OR NOT contains(completed_stages, :stageName))")
                    .expressionAttributeValues(Map.of(
                            ":empty", AttributeValue.fromL(List.of()),
                            ":stage", AttributeValue.fromL(List.of(AttributeValue.fromS(stageName))),
                            ":stageName", AttributeValue.fromS(stageName)
                    ))
                    .build());
        } catch (ConditionalCheckFailedException ex) {
            if (hasCompletedStage(sessionId, stageName)) {
                return;
            }
            throw ex;
        }
    }

    /**
     * Atomically appends documentId to the document_ids list if not already present.
     * On ConditionalCheckFailedException caused by a duplicate document, this is a no-op.
     * If the session is missing, the original exception is rethrown.
     */
    public void attachDocument(String sessionId, String documentId) {
        try {
            dynamoDbClient.updateItem(UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of("id", AttributeValue.fromS(sessionId)))
                    .updateExpression(
                            "SET document_ids = list_append(if_not_exists(document_ids, :empty), :doc), updatedAt = :ts")
                    .conditionExpression(
                            "attribute_exists(id) AND (attribute_not_exists(document_ids) OR NOT contains(document_ids, :docName))")
                    .expressionAttributeValues(Map.of(
                            ":empty", AttributeValue.fromL(List.of()),
                            ":doc", AttributeValue.fromL(List.of(AttributeValue.fromS(documentId))),
                            ":docName", AttributeValue.fromS(documentId),
                            ":ts", AttributeValue.fromS(Instant.now().toString())
                    ))
                    .build());
        } catch (ConditionalCheckFailedException ex) {
            if (hasAttachedDocument(sessionId, documentId)) {
                return;
            }
            throw ex;
        }
    }

    private boolean hasCompletedStage(String sessionId, String stageName) {
        return findById(sessionId)
                .map(Session::getCompletedStages)
                .map(stages -> stages.contains(stageName))
                .orElse(false);
    }

    private boolean hasAttachedDocument(String sessionId, String documentId) {
        return findById(sessionId)
                .map(Session::getDocumentIds)
                .map(documentIds -> documentIds.contains(documentId))
                .orElse(false);
    }
}
