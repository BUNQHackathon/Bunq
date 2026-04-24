package com.bunq.javabackend.model.control;

import com.bunq.javabackend.model.enums.TestingStatus;
import software.amazon.awssdk.enhanced.dynamodb.AttributeConverter;
import software.amazon.awssdk.enhanced.dynamodb.AttributeValueType;
import software.amazon.awssdk.enhanced.dynamodb.EnhancedType;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

public class TestingStatusConverter implements AttributeConverter<TestingStatus> {

    @Override
    public AttributeValue transformFrom(TestingStatus input) {
        if (input == null) return AttributeValue.builder().nul(true).build();
        return AttributeValue.builder().s(input.name()).build();
    }

    @Override
    public TestingStatus transformTo(AttributeValue input) {
        if (input == null || input.s() == null) return null;
        return TestingStatus.valueOf(input.s());
    }

    @Override
    public EnhancedType<TestingStatus> type() {
        return EnhancedType.of(TestingStatus.class);
    }

    @Override
    public AttributeValueType attributeValueType() {
        return AttributeValueType.S;
    }
}
