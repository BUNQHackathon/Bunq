package com.bunq.javabackend.model.gap;

import com.bunq.javabackend.model.enums.Priority;
import software.amazon.awssdk.enhanced.dynamodb.AttributeConverter;
import software.amazon.awssdk.enhanced.dynamodb.AttributeValueType;
import software.amazon.awssdk.enhanced.dynamodb.EnhancedType;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

public class PriorityConverter implements AttributeConverter<Priority> {

    @Override
    public AttributeValue transformFrom(Priority input) {
        if (input == null) return AttributeValue.builder().nul(true).build();
        return AttributeValue.builder().s(input.name()).build();
    }

    @Override
    public Priority transformTo(AttributeValue input) {
        if (input == null || input.s() == null) return null;
        return Priority.valueOf(input.s());
    }

    @Override
    public EnhancedType<Priority> type() {
        return EnhancedType.of(Priority.class);
    }

    @Override
    public AttributeValueType attributeValueType() {
        return AttributeValueType.S;
    }
}
