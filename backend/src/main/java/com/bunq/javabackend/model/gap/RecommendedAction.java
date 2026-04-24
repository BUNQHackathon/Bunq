package com.bunq.javabackend.model.gap;

import com.bunq.javabackend.model.enums.Priority;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbConvertedBy;

@DynamoDbBean
@NoArgsConstructor
@Setter
public class RecommendedAction {

    @Getter(onMethod_ = @DynamoDbAttribute("action"))
    private String action;

    @Getter(onMethod_ = {@DynamoDbAttribute("priority"), @DynamoDbConvertedBy(PriorityConverter.class)})
    private Priority priority;

    @Getter(onMethod_ = @DynamoDbAttribute("effort_days"))
    private Integer effortDays;

    @Getter(onMethod_ = @DynamoDbAttribute("suggested_owner"))
    private String suggestedOwner;
}
