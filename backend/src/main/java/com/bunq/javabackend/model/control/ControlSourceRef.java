package com.bunq.javabackend.model.control;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;

@DynamoDbBean
@NoArgsConstructor
@Setter
public class ControlSourceRef {

    @Getter(onMethod_ = @DynamoDbAttribute("bank"))
    private String bank;

    @Getter(onMethod_ = @DynamoDbAttribute("doc"))
    private String doc;

    @Getter(onMethod_ = @DynamoDbAttribute("section_id"))
    private String sectionId;

    @Getter(onMethod_ = @DynamoDbAttribute("kb_chunk_id"))
    private String kbChunkId;
}
