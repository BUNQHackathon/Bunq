package com.bunq.javabackend.dto.response;

import java.util.List;

public record RagResponse(String answer, List<Citation> citations) {}
