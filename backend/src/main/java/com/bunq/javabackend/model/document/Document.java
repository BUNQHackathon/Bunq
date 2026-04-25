package com.bunq.javabackend.model.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;

import java.time.Instant;
import java.util.Set;

@DynamoDbBean
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Setter
public class Document {

    /** SHA-256 hex, lowercase, 64 chars — content-addressable PK */
    @Getter(onMethod_ = {@DynamoDbPartitionKey, @DynamoDbAttribute("id")})
    private String id;

    @Getter(onMethod_ = @DynamoDbAttribute("filename"))
    private String filename;

    /** Nullable — curated human-readable title; falls back to filename in the UI */
    @Getter(onMethod_ = @DynamoDbAttribute("display_name"))
    private String displayName;

    @Getter(onMethod_ = @DynamoDbAttribute("content_type"))
    private String contentType;

    @Getter(onMethod_ = @DynamoDbAttribute("size_bytes"))
    private Long sizeBytes;

    @Getter(onMethod_ = @DynamoDbAttribute("s3_key"))
    private String s3Key;

    /** kind ∈ "regulation" | "policy" | "brief" | "evidence" | "audio" | "other" */
    @Getter(onMethod_ = {
        @DynamoDbAttribute("kind"),
        @DynamoDbSecondaryPartitionKey(indexNames = "kind-last-used-at-index")
    })
    private String kind;

    @Getter(onMethod_ = @DynamoDbAttribute("jurisdictions"))
    private Set<String> jurisdictions;

    @Getter(onMethod_ = @DynamoDbAttribute("first_seen_at"))
    private Instant firstSeenAt;

    @Getter(onMethod_ = {
        @DynamoDbAttribute("last_used_at"),
        @DynamoDbSecondarySortKey(indexNames = "kind-last-used-at-index")
    })
    private Instant lastUsedAt;

    /** Nullable — populated after Textract completes */
    @Getter(onMethod_ = @DynamoDbAttribute("extracted_text"))
    private String extractedText;

    /** S3 key for extracted text when text is offloaded to S3 (>400KB DDB limit) */
    @Getter(onMethod_ = @DynamoDbAttribute("extraction_s3_key"))
    private String extractionS3Key;

    @Getter(onMethod_ = @DynamoDbAttribute("extracted_at"))
    private Instant extractedAt;

    @Getter(onMethod_ = @DynamoDbAttribute("page_count"))
    private Integer pageCount;

    @Builder.Default
    @Getter(onMethod_ = @DynamoDbAttribute("obligations_extracted"))
    private boolean obligationsExtracted = false;

    @Builder.Default
    @Getter(onMethod_ = @DynamoDbAttribute("controls_extracted"))
    private boolean controlsExtracted = false;
}
