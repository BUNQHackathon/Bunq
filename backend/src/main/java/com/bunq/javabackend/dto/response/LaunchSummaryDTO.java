package com.bunq.javabackend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LaunchSummaryDTO {
    private String id;
    private String name;
    private String license;
    private String status;
    private int counterpartiesCount;
    private int jurisdictionCount;
    private String createdAt;
    private String updatedAt;
}
