package com.bunq.javabackend.model.control;

import com.bunq.javabackend.model.enums.ControlCategory;
import software.amazon.awssdk.enhanced.dynamodb.AttributeConverter;
import software.amazon.awssdk.enhanced.dynamodb.AttributeValueType;
import software.amazon.awssdk.enhanced.dynamodb.EnhancedType;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

public class ControlCategoryConverter implements AttributeConverter<ControlCategory> {

    @Override
    public AttributeValue transformFrom(ControlCategory input) {
        if (input == null) return AttributeValue.builder().nul(true).build();
        return AttributeValue.builder().s(input.name()).build();
    }

    @Override
    public ControlCategory transformTo(AttributeValue input) {
        if (input == null || input.s() == null) return null;
        return ControlCategory.valueOf(input.s());
    }

    @Override
    public EnhancedType<ControlCategory> type() {
        return EnhancedType.of(ControlCategory.class);
    }

    @Override
    public AttributeValueType attributeValueType() {
        return AttributeValueType.S;
    }
}
