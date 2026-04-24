package com.bunq.javabackend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UploadRecordDTO {
    private String s3Key;
    private String fileKind;
    private String uploadedAt;
    private String presignedGetUrl;
}
