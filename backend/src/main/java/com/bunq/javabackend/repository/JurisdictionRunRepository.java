package com.bunq.javabackend.repository;

import com.bunq.javabackend.model.launch.JurisdictionRun;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;

import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;

@Repository
public class JurisdictionRunRepository {

    private final DynamoDbTable<JurisdictionRun> table;

    public JurisdictionRunRepository(
            DynamoDbEnhancedClient client,
            @Value("${aws.dynamodb.jurisdiction-runs-table}") String tableName) {
        this.table = client.table(tableName, TableSchema.fromBean(JurisdictionRun.class));
    }

    public Optional<JurisdictionRun> findByLaunchIdAndCode(String launchId, String jurisdictionCode) {
        var key = Key.builder()
                .partitionValue(launchId)
                .sortValue(jurisdictionCode)
                .build();
        return Optional.ofNullable(table.getItem(key));
    }

    public void save(JurisdictionRun run) {
        table.putItem(run);
    }

    public List<JurisdictionRun> findByLaunchId(String launchId) {
        var key = Key.builder()
                .partitionValue(launchId)
                .build();
        return StreamSupport.stream(
                table.query(QueryConditional.keyEqualTo(key)).items().spliterator(),
                false
        ).toList();
    }

    public List<JurisdictionRun> findByJurisdiction(String jurisdictionCode) {
        var index = table.index("jurisdiction-index");
        var qc = QueryConditional.keyEqualTo(Key.builder().partitionValue(jurisdictionCode).build());
        return index.query(r -> r.queryConditional(qc))
                .stream()
                .flatMap(p -> p.items().stream())
                .toList();
    }

    public List<JurisdictionRun> findAll() {
        return StreamSupport.stream(table.scan().items().spliterator(), false).toList();
    }
}
