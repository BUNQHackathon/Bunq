package com.bunq.javabackend.model.control;

import com.bunq.javabackend.model.enums.ImplementationStatus;
import software.amazon.awssdk.enhanced.dynamodb.AttributeConverter;
import software.amazon.awssdk.enhanced.dynamodb.AttributeValueType;
import software.amazon.awssdk.enhanced.dynamodb.EnhancedType;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

public class ImplementationStatusConverter implements AttributeConverter<ImplementationStatus> {

    @Override
    public AttributeValue transformFrom(ImplementationStatus input) {
        if (input == null) return AttributeValue.builder().nul(true).build();
        return AttributeValue.builder().s(input.name()).build();
    }

    @Override
    public ImplementationStatus transformTo(AttributeValue input) {
        if (input == null || input.s() == null) return null;
        return ImplementationStatus.valueOf(input.s());
    }

    @Override
    public EnhancedType<ImplementationStatus> type() {
        return EnhancedType.of(ImplementationStatus.class);
    }

    @Override
    public AttributeValueType attributeValueType() {
        return AttributeValueType.S;
    }
}
