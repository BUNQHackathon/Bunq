package com.bunq.javabackend.model.gap;

import com.bunq.javabackend.model.enums.GapType;
import software.amazon.awssdk.enhanced.dynamodb.AttributeConverter;
import software.amazon.awssdk.enhanced.dynamodb.AttributeValueType;
import software.amazon.awssdk.enhanced.dynamodb.EnhancedType;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

public class GapTypeConverter implements AttributeConverter<GapType> {

    @Override
    public AttributeValue transformFrom(GapType input) {
        if (input == null) return AttributeValue.builder().nul(true).build();
        return AttributeValue.builder().s(input.name()).build();
    }

    @Override
    public GapType transformTo(AttributeValue input) {
        if (input == null || input.s() == null) return null;
        return GapType.valueOf(input.s());
    }

    @Override
    public EnhancedType<GapType> type() {
        return EnhancedType.of(GapType.class);
    }

    @Override
    public AttributeValueType attributeValueType() {
        return AttributeValueType.S;
    }
}
