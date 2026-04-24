package com.bunq.javabackend.model.gap;

import com.bunq.javabackend.model.enums.Severity;
import software.amazon.awssdk.enhanced.dynamodb.AttributeConverter;
import software.amazon.awssdk.enhanced.dynamodb.AttributeValueType;
import software.amazon.awssdk.enhanced.dynamodb.EnhancedType;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

public class SeverityConverter implements AttributeConverter<Severity> {

    @Override
    public AttributeValue transformFrom(Severity input) {
        if (input == null) return AttributeValue.builder().nul(true).build();
        return AttributeValue.builder().s(input.name()).build();
    }

    @Override
    public Severity transformTo(AttributeValue input) {
        if (input == null || input.s() == null) return null;
        return Severity.valueOf(input.s());
    }

    @Override
    public EnhancedType<Severity> type() {
        return EnhancedType.of(Severity.class);
    }

    @Override
    public AttributeValueType attributeValueType() {
        return AttributeValueType.S;
    }
}
