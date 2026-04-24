package com.bunq.javabackend.model.obligation;

import com.bunq.javabackend.model.enums.ObligationType;
import software.amazon.awssdk.enhanced.dynamodb.AttributeConverter;
import software.amazon.awssdk.enhanced.dynamodb.AttributeValueType;
import software.amazon.awssdk.enhanced.dynamodb.EnhancedType;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

public class ObligationTypeConverter implements AttributeConverter<ObligationType> {

    @Override
    public AttributeValue transformFrom(ObligationType input) {
        if (input == null) return AttributeValue.builder().nul(true).build();
        return AttributeValue.builder().s(input.name()).build();
    }

    @Override
    public ObligationType transformTo(AttributeValue input) {
        if (input == null || input.s() == null) return null;
        return ObligationType.valueOf(input.s());
    }

    @Override
    public EnhancedType<ObligationType> type() {
        return EnhancedType.of(ObligationType.class);
    }

    @Override
    public AttributeValueType attributeValueType() {
        return AttributeValueType.S;
    }
}
