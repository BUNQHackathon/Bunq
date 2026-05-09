package com.bunq.javabackend.helper.dynamodb;

import software.amazon.awssdk.enhanced.dynamodb.AttributeConverter;
import software.amazon.awssdk.enhanced.dynamodb.AttributeValueType;
import software.amazon.awssdk.enhanced.dynamodb.EnhancedType;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.LocalDate;

public class LocalDateAttributeConverter implements AttributeConverter<LocalDate> {

    @Override
    public AttributeValue transformFrom(LocalDate input) {
        if (input == null) return AttributeValue.builder().nul(true).build();
        return AttributeValue.builder().s(input.toString()).build();
    }

    @Override
    public LocalDate transformTo(AttributeValue input) {
        if (input == null || input.s() == null) return null;
        return LocalDate.parse(input.s());
    }

    @Override
    public EnhancedType<LocalDate> type() {
        return EnhancedType.of(LocalDate.class);
    }

    @Override
    public AttributeValueType attributeValueType() {
        return AttributeValueType.S;
    }
}
