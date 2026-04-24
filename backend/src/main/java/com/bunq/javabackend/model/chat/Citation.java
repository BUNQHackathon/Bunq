package com.bunq.javabackend.model.chat;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;

@DynamoDbBean
@NoArgsConstructor
@Setter
public class Citation {

    @Getter(onMethod_ = @DynamoDbAttribute("kbType"))
    private String kbType;

    @Getter(onMethod_ = @DynamoDbAttribute("chunkId"))
    private String chunkId;

    @Getter(onMethod_ = @DynamoDbAttribute("score"))
    private Double score;

    @Getter(onMethod_ = @DynamoDbAttribute("s3Uri"))
    private String s3Uri;

    @Getter(onMethod_ = @DynamoDbAttribute("sourceText"))
    private String sourceText;
}
