package com.bunq.javabackend.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String correlationId = UUID.randomUUID().toString();
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> Map.of(
                        "field", f.getField(),
                        "message", Objects.requireNonNullElse(f.getDefaultMessage(), "invalid")))
                .toList();
        log.warn("validation failed correlationId={} errors={}", correlationId, errors);
        return ResponseEntity.badRequest().body(Map.of(
                "correlationId", correlationId,
                "validation_errors", errors));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException ex) {
        String correlationId = UUID.randomUUID().toString();
        log.warn("illegal state transition correlationId={} message={}", correlationId, ex.getMessage());
        return ResponseEntity.status(409).body(Map.of(
                "correlationId", correlationId,
                "message", ex.getMessage()));
    }
}
