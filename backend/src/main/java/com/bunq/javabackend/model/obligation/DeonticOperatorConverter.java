package com.bunq.javabackend.model.obligation;

import com.bunq.javabackend.model.enums.DeonticOperator;
import software.amazon.awssdk.enhanced.dynamodb.AttributeConverter;
import software.amazon.awssdk.enhanced.dynamodb.AttributeValueType;
import software.amazon.awssdk.enhanced.dynamodb.EnhancedType;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

public class DeonticOperatorConverter implements AttributeConverter<DeonticOperator> {

    @Override
    public AttributeValue transformFrom(DeonticOperator input) {
        if (input == null) return AttributeValue.builder().nul(true).build();
        return AttributeValue.builder().s(input.name()).build();
    }

    @Override
    public DeonticOperator transformTo(AttributeValue input) {
        if (input == null || input.s() == null) return null;
        return DeonticOperator.valueOf(input.s());
    }

    @Override
    public EnhancedType<DeonticOperator> type() {
        return EnhancedType.of(DeonticOperator.class);
    }

    @Override
    public AttributeValueType attributeValueType() {
        return AttributeValueType.S;
    }
}
