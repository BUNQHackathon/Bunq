package com.bunq.javabackend.service.ai.bedrock;

public class BedrockBackpressureException extends RuntimeException {
    public BedrockBackpressureException(String message) {
        super(message);
    }
}
