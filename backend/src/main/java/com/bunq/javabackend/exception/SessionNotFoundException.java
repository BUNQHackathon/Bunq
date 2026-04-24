package com.bunq.javabackend.exception;

public class SessionNotFoundException extends RuntimeException {
    public SessionNotFoundException(String id) {
        super("Session not found: " + id);
    }
}
