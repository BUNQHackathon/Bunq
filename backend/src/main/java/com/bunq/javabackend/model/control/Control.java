package com.bunq.javabackend.model.control;

import com.bunq.javabackend.model.enums.ControlCategory;
import com.bunq.javabackend.model.enums.ControlType;
import com.bunq.javabackend.model.enums.ImplementationStatus;
import com.bunq.javabackend.model.enums.TestingStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbConvertedBy;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;

import java.time.LocalDate;
import java.util.List;

@DynamoDbBean
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Setter
public class Control {

    @Getter(onMethod_ = {@DynamoDbPartitionKey, @DynamoDbAttribute("id")})
    private String id;

    @Getter(onMethod_ = {@DynamoDbAttribute("control_type"), @DynamoDbConvertedBy(ControlTypeConverter.class)})
    private ControlType controlType;

    @Getter(onMethod_ = {@DynamoDbAttribute("category"), @DynamoDbConvertedBy(ControlCategoryConverter.class)})
    private ControlCategory category;

    @Getter(onMethod_ = @DynamoDbAttribute("description"))
    private String description;

    @Getter(onMethod_ = @DynamoDbAttribute("owner"))
    private String owner;

    @Getter(onMethod_ = @DynamoDbAttribute("testing_cadence"))
    private String testingCadence;

    @Getter(onMethod_ = @DynamoDbAttribute("evidence_type"))
    private String evidenceType;

    @Getter(onMethod_ = @DynamoDbAttribute("last_tested"))
    private LocalDate lastTested;

    @Getter(onMethod_ = {@DynamoDbAttribute("testing_status"), @DynamoDbConvertedBy(TestingStatusConverter.class)})
    private TestingStatus testingStatus;

    @Getter(onMethod_ = {@DynamoDbAttribute("implementation_status"), @DynamoDbConvertedBy(ImplementationStatusConverter.class)})
    private ImplementationStatus implementationStatus;

    @Getter(onMethod_ = @DynamoDbAttribute("mapped_standards"))
    private List<String> mappedStandards;

    @Getter(onMethod_ = @DynamoDbAttribute("linked_tools"))
    private List<String> linkedTools;

    @Getter(onMethod_ = @DynamoDbAttribute("source_doc_ref"))
    private ControlSourceRef sourceDocRef;

    @Getter(onMethod_ = @DynamoDbAttribute("session_id"))
    private String sessionId;

    @Getter(onMethod_ = @DynamoDbAttribute("bank_id"))
    private String bankId;

    @Getter(onMethod_ = {
        @DynamoDbAttribute("document_id"),
        @DynamoDbSecondaryPartitionKey(indexNames = "document-id-index")
    })
    private String documentId;
}
