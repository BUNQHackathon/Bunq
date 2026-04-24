package com.bunq.javabackend.model.launch;

import software.amazon.awssdk.enhanced.dynamodb.AttributeConverter;
import software.amazon.awssdk.enhanced.dynamodb.AttributeValueType;
import software.amazon.awssdk.enhanced.dynamodb.EnhancedType;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

public class LaunchKindConverter implements AttributeConverter<LaunchKind> {

    @Override
    public AttributeValue transformFrom(LaunchKind input) {
        if (input == null) return AttributeValue.builder().nul(true).build();
        return AttributeValue.builder().s(input.name()).build();
    }

    @Override
    public LaunchKind transformTo(AttributeValue input) {
        if (input == null || input.s() == null) return null;
        return LaunchKind.valueOf(input.s());
    }

    @Override
    public EnhancedType<LaunchKind> type() {
        return EnhancedType.of(LaunchKind.class);
    }

    @Override
    public AttributeValueType attributeValueType() {
        return AttributeValueType.S;
    }
}
