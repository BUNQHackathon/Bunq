package com.bunq.javabackend.model.gap;

import com.bunq.javabackend.model.enums.GapStatus;
import software.amazon.awssdk.enhanced.dynamodb.AttributeConverter;
import software.amazon.awssdk.enhanced.dynamodb.AttributeValueType;
import software.amazon.awssdk.enhanced.dynamodb.EnhancedType;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

public class GapStatusConverter implements AttributeConverter<GapStatus> {

    @Override
    public AttributeValue transformFrom(GapStatus input) {
        if (input == null) return AttributeValue.builder().nul(true).build();
        return AttributeValue.builder().s(input.name()).build();
    }

    @Override
    public GapStatus transformTo(AttributeValue input) {
        if (input == null || input.s() == null) return null;
        return GapStatus.valueOf(input.s());
    }

    @Override
    public EnhancedType<GapStatus> type() {
        return EnhancedType.of(GapStatus.class);
    }

    @Override
    public AttributeValueType attributeValueType() {
        return AttributeValueType.S;
    }
}
