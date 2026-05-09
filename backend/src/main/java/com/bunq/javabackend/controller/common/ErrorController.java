package com.bunq.javabackend.controller.common;

import com.bunq.javabackend.exception.EntityAlreadyExistsException;
import com.bunq.javabackend.exception.ForbiddenException;
import com.bunq.javabackend.exception.NotFoundException;
import com.bunq.javabackend.exception.PipelineStageException;
import com.bunq.javabackend.exception.SessionNotFoundException;
import com.bunq.javabackend.exception.SidecarCommunicationException;
import com.bunq.javabackend.exception.ValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@ControllerAdvice
@RequiredArgsConstructor
public class ErrorController {

    @ExceptionHandler(SessionNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleSessionNotFound(SessionNotFoundException ex) {
        return generateErrorResponse(ex.getMessage(), HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(NotFoundException ex) {
        return generateErrorResponse(ex.getMessage(), HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(EntityAlreadyExistsException.class)
    public ResponseEntity<Map<String, String>> handleAlreadyExists(EntityAlreadyExistsException ex) {
        return generateErrorResponse(ex.getMessage(), HttpStatus.CONFLICT);
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, String>> handleForbidden(ForbiddenException ex) {
        return generateErrorResponse(ex.getMessage(), HttpStatus.FORBIDDEN);
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<Map<String, String>> handleValidation(ValidationException ex) {
        return generateErrorResponse(ex.getMessage(), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(SidecarCommunicationException.class)
    public ResponseEntity<Map<String, String>> handleSidecarCommunication(SidecarCommunicationException ex) {
        log.error("Sidecar communication failure [correlationId={}]", MDC.get("correlationId"), ex);
        return generateErrorResponse(ex.getMessage(), HttpStatus.BAD_GATEWAY);
    }

    @ExceptionHandler(PipelineStageException.class)
    public ResponseEntity<Map<String, String>> handlePipelineStage(PipelineStageException ex) {
        log.error("Pipeline stage failure stage={} [correlationId={}]", ex.getStage(), MDC.get("correlationId"), ex);
        return generateErrorResponse(ex.getMessage(), HttpStatus.BAD_GATEWAY);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        return generateErrorResponse(ex.getMessage(), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleJsonParseError(HttpMessageNotReadableException ex) {
        return generateErrorResponse("Invalid request format or enum value", HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleResponseStatus(ResponseStatusException ex) {
        return generateErrorResponse(ex.getReason() != null ? ex.getReason() : ex.getMessage(),
                HttpStatus.valueOf(ex.getStatusCode().value()));
    }

    // Catch-all — must remain last so specific handlers above take precedence
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> handleOther(RuntimeException ex) {
        log.error("Unhandled RuntimeException [correlationId={}]", MDC.get("correlationId"), ex);
        return generateErrorResponse("Unknown server error", HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private ResponseEntity<Map<String, String>> generateErrorResponse(String message, HttpStatus status) {
        Map<String, String> error = new HashMap<>();
        error.put("message", message);
        String correlationId = MDC.get("correlationId");
        // Always emit correlationId so clients can correlate errors with logs
        error.put("correlationId", correlationId != null ? correlationId : UUID.randomUUID().toString());
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(error);
    }
}
