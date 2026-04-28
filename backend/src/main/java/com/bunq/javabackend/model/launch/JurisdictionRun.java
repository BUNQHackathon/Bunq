package com.bunq.javabackend.model.launch;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import com.bunq.javabackend.model.enums.RunStatus;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbConvertedBy;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

@DynamoDbBean
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Setter
public class JurisdictionRun {

    @Getter(onMethod_ = {@DynamoDbPartitionKey, @DynamoDbSecondarySortKey(indexNames = "jurisdiction-index"), @DynamoDbAttribute("launch_id")})
    private String launchId;

    @Getter(onMethod_ = {@DynamoDbSortKey, @DynamoDbSecondaryPartitionKey(indexNames = "jurisdiction-index"), @DynamoDbAttribute("jurisdiction_code")})
    private String jurisdictionCode;

    @Getter(onMethod_ = @DynamoDbAttribute("current_session_id"))
    private String currentSessionId;

    @Getter(onMethod_ = @DynamoDbAttribute("verdict"))
    private String verdict;

    @Getter(onMethod_ = @DynamoDbAttribute("gaps_count"))
    private Integer gapsCount;

    @Getter(onMethod_ = @DynamoDbAttribute("sanctions_hits"))
    private Integer sanctionsHits;

    @Getter(onMethod_ = @DynamoDbAttribute("proof_pack_s3_key"))
    private String proofPackS3Key;

    @Getter(onMethod_ = @DynamoDbAttribute("last_run_at"))
    private String lastRunAt;

    @Getter(onMethod_ = {@DynamoDbAttribute("status"), @DynamoDbConvertedBy(RunStatusConverter.class)})
    private RunStatus status;

    @Getter(onMethod_ = @DynamoDbAttribute("failed_stage"))
    private String failedStage;

    @Getter(onMethod_ = @DynamoDbAttribute("last_error"))
    private String lastError;
}
