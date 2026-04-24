package com.bunq.javabackend.model.mapping;

import com.bunq.javabackend.model.enums.MappingType;
import software.amazon.awssdk.enhanced.dynamodb.AttributeConverter;
import software.amazon.awssdk.enhanced.dynamodb.AttributeValueType;
import software.amazon.awssdk.enhanced.dynamodb.EnhancedType;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

public class MappingTypeConverter implements AttributeConverter<MappingType> {

    @Override
    public AttributeValue transformFrom(MappingType input) {
        if (input == null) return AttributeValue.builder().nul(true).build();
        return AttributeValue.builder().s(input.name()).build();
    }

    @Override
    public MappingType transformTo(AttributeValue input) {
        if (input == null || input.s() == null) return null;
        return MappingType.valueOf(input.s());
    }

    @Override
    public EnhancedType<MappingType> type() {
        return EnhancedType.of(MappingType.class);
    }

    @Override
    public AttributeValueType attributeValueType() {
        return AttributeValueType.S;
    }
}
