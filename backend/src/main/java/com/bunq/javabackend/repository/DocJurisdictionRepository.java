package com.bunq.javabackend.repository;

import com.bunq.javabackend.model.document.DocJurisdictionItem;
import com.bunq.javabackend.model.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;

import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;

@Repository
public class DocJurisdictionRepository {

    private final DynamoDbTable<DocJurisdictionItem> table;

    public DocJurisdictionRepository(
            DynamoDbEnhancedClient client,
            @Value("${aws.dynamodb.doc-jurisdictions-table}") String tableName) {
        this.table = client.table(tableName, TableSchema.fromBean(DocJurisdictionItem.class));
    }

    public void putAll(String documentId, Set<String> jurisdictions, Document doc) {
        if (jurisdictions == null || jurisdictions.isEmpty()) return;
        for (String j : jurisdictions) {
            DocJurisdictionItem item = DocJurisdictionItem.builder()
                    .jurisdiction(j.toUpperCase())
                    .documentId(documentId)
                    .kind(doc.getKind())
                    .filename(doc.getFilename())
                    .lastUsedAt(doc.getLastUsedAt())
                    .build();
            table.putItem(item);
        }
    }

    public List<DocJurisdictionItem> findByJurisdiction(String code) {
        var qc = QueryConditional.keyEqualTo(Key.builder().partitionValue(code.toUpperCase()).build());
        return StreamSupport.stream(table.query(qc).items().spliterator(), false).toList();
    }

    public void deleteAll(String documentId, Set<String> jurisdictions) {
        if (jurisdictions == null || jurisdictions.isEmpty()) return;
        for (String j : jurisdictions) {
            table.deleteItem(Key.builder()
                    .partitionValue(j.toUpperCase())
                    .sortValue(documentId)
                    .build());
        }
    }
}
