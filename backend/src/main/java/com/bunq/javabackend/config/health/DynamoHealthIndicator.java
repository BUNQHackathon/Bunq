package com.bunq.javabackend.config.health;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;

@Component("dynamo")
@RequiredArgsConstructor
public class DynamoHealthIndicator implements HealthIndicator {

    private final DynamoDbClient dynamoDbClient;

    @Value("${aws.dynamodb.sessions-table}")
    private String sessionsTable;

    @Override
    public Health health() {
        try {
            dynamoDbClient.describeTable(
                    DescribeTableRequest.builder().tableName(sessionsTable).build());
            return Health.up().build();
        } catch (Exception e) {
            return Health.down().withDetail("error", e.getMessage()).build();
        }
    }
}
