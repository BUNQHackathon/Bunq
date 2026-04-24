package com.bunq.javabackend.model.control;

import com.bunq.javabackend.model.enums.ControlType;
import software.amazon.awssdk.enhanced.dynamodb.AttributeConverter;
import software.amazon.awssdk.enhanced.dynamodb.AttributeValueType;
import software.amazon.awssdk.enhanced.dynamodb.EnhancedType;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

public class ControlTypeConverter implements AttributeConverter<ControlType> {

    @Override
    public AttributeValue transformFrom(ControlType input) {
        if (input == null) return AttributeValue.builder().nul(true).build();
        return AttributeValue.builder().s(input.name()).build();
    }

    @Override
    public ControlType transformTo(AttributeValue input) {
        if (input == null || input.s() == null) return null;
        return ControlType.valueOf(input.s());
    }

    @Override
    public EnhancedType<ControlType> type() {
        return EnhancedType.of(ControlType.class);
    }

    @Override
    public AttributeValueType attributeValueType() {
        return AttributeValueType.S;
    }
}
