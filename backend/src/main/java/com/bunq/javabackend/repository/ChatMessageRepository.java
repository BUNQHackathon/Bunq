package com.bunq.javabackend.repository;

import com.bunq.javabackend.model.chat.ChatMessage;
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
public class ChatMessageRepository {

    private final DynamoDbTable<ChatMessage> table;

    public ChatMessageRepository(
            DynamoDbEnhancedClient client,
            @Value("${aws.dynamodb.chat-messages-table}") String tableName) {
        this.table = client.table(tableName, TableSchema.fromBean(ChatMessage.class));
    }

    public void save(ChatMessage message) {
        table.putItem(message);
    }

    public Optional<ChatMessage> findById(String id) {
        return Optional.ofNullable(table.getItem(Key.builder().partitionValue(id).build()));
    }

    public List<ChatMessage> findByChatId(String chatId, int limit) {
        QueryEnhancedRequest request = QueryEnhancedRequest.builder()
                .queryConditional(QueryConditional.keyEqualTo(
                        Key.builder().partitionValue(chatId).build()))
                .scanIndexForward(true)
                .limit(limit)
                .build();
        DynamoDbIndex<ChatMessage> index = table.index("chat_id-timestamp-index");
        return index.query(request).stream()
                .flatMap(page -> StreamSupport.stream(page.items().spliterator(), false))
                .toList();
    }

    /**
     * Full scan across all chat messages. Intended for the small-cardinality
     * chat-summary listing — callers must do the chatId grouping themselves.
     */
    public List<ChatMessage> findAll() {
        return StreamSupport.stream(table.scan().items().spliterator(), false).toList();
    }
}
