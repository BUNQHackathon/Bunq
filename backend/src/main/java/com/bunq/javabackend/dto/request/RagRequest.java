package com.bunq.javabackend.dto.request;

import jakarta.validation.constraints.NotBlank;

public record RagRequest(@NotBlank String query, String jurisdiction) {}
