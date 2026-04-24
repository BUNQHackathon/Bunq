package com.bunq.javabackend.model.obligation;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;

@DynamoDbBean
@NoArgsConstructor
@Setter
public class ObligationSource {

    @Getter(onMethod_ = @DynamoDbAttribute("regulation"))
    private String regulation;

    @Getter(onMethod_ = @DynamoDbAttribute("article"))
    private String article;

    @Getter(onMethod_ = @DynamoDbAttribute("section"))
    private String section;

    @Getter(onMethod_ = @DynamoDbAttribute("paragraph"))
    private Integer paragraph;

    @Getter(onMethod_ = @DynamoDbAttribute("source_text"))
    private String sourceText;

    @Getter(onMethod_ = @DynamoDbAttribute("retrieved_from_kb_chunk_id"))
    private String retrievedFromKbChunkId;
}
