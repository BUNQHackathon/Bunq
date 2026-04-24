package com.bunq.javabackend.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ChatRequestDTO {

    @NotBlank
    private String query;

    private String chatId;

    private String sessionId;
}
