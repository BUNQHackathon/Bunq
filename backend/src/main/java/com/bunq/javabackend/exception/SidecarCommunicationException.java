package com.bunq.javabackend.exception;

public class SidecarCommunicationException extends RuntimeException {
    public SidecarCommunicationException(String message, Throwable cause) {
        super(message, cause);
    }
}
