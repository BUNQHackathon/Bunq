package com.bunq.javabackend.exception;

public class GapScoringException extends RuntimeException {

    public GapScoringException(String message) {
        super(message);
    }

    public GapScoringException(String message, Throwable cause) {
        super(message, cause);
    }
}
