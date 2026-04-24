package com.bunq.javabackend.model.session;

import com.bunq.javabackend.model.enums.SessionState;
import software.amazon.awssdk.enhanced.dynamodb.AttributeConverter;
import software.amazon.awssdk.enhanced.dynamodb.AttributeValueType;
import software.amazon.awssdk.enhanced.dynamodb.EnhancedType;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

public class SessionStateConverter implements AttributeConverter<SessionState> {

    @Override
    public AttributeValue transformFrom(SessionState input) {
        if (input == null) return AttributeValue.builder().nul(true).build();
        return AttributeValue.builder().s(input.name()).build();
    }

    @Override
    public SessionState transformTo(AttributeValue input) {
        if (input == null || input.s() == null) return null;
        return SessionState.valueOf(input.s());
    }

    @Override
    public EnhancedType<SessionState> type() {
        return EnhancedType.of(SessionState.class);
    }

    @Override
    public AttributeValueType attributeValueType() {
        return AttributeValueType.S;
    }
}
