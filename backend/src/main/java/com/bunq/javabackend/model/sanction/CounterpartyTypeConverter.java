package com.bunq.javabackend.model.sanction;

import com.bunq.javabackend.model.enums.CounterpartyType;
import software.amazon.awssdk.enhanced.dynamodb.AttributeConverter;
import software.amazon.awssdk.enhanced.dynamodb.AttributeValueType;
import software.amazon.awssdk.enhanced.dynamodb.EnhancedType;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

public class CounterpartyTypeConverter implements AttributeConverter<CounterpartyType> {

    @Override
    public AttributeValue transformFrom(CounterpartyType input) {
        if (input == null) return AttributeValue.builder().nul(true).build();
        return AttributeValue.builder().s(input.name()).build();
    }

    @Override
    public CounterpartyType transformTo(AttributeValue input) {
        if (input == null || input.s() == null) return null;
        return CounterpartyType.valueOf(input.s());
    }

    @Override
    public EnhancedType<CounterpartyType> type() {
        return EnhancedType.of(CounterpartyType.class);
    }

    @Override
    public AttributeValueType attributeValueType() {
        return AttributeValueType.S;
    }
}
