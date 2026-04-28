package com.bunq.javabackend.model.launch;

import com.bunq.javabackend.model.enums.RunStatus;
import software.amazon.awssdk.enhanced.dynamodb.AttributeConverter;
import software.amazon.awssdk.enhanced.dynamodb.AttributeValueType;
import software.amazon.awssdk.enhanced.dynamodb.EnhancedType;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

public class RunStatusConverter implements AttributeConverter<RunStatus> {

    @Override
    public AttributeValue transformFrom(RunStatus input) {
        if (input == null) return AttributeValue.builder().nul(true).build();
        return AttributeValue.builder().s(input.name()).build();
    }

    @Override
    public RunStatus transformTo(AttributeValue input) {
        if (input == null || input.s() == null) return null;
        return RunStatus.valueOf(input.s());
    }

    @Override
    public EnhancedType<RunStatus> type() {
        return EnhancedType.of(RunStatus.class);
    }

    @Override
    public AttributeValueType attributeValueType() {
        return AttributeValueType.S;
    }
}
