package com.bunq.javabackend.model.sanction;

import com.bunq.javabackend.model.enums.CounterpartyType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbConvertedBy;

@DynamoDbBean
@NoArgsConstructor
@Setter
public class Counterparty {

    @Getter(onMethod_ = @DynamoDbAttribute("name"))
    private String name;

    @Getter(onMethod_ = @DynamoDbAttribute("country"))
    private String country;

    @Getter(onMethod_ = {@DynamoDbAttribute("type"), @DynamoDbConvertedBy(CounterpartyTypeConverter.class)})
    private CounterpartyType type;
}
