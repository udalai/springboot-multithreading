package com.uma.executor.api.springbootmultithreading.controller;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    // 1. Intercepts ALL built-in Spring MVC errors (404 Not Found, 405 Method Not Allowed, missing params, etc.)
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {

        HttpStatus status = HttpStatus.valueOf(statusCode.value());
        Map<String, Object> errorBody = Map.of(
                "timestamp", Instant.now().toString(),
                "status", status.value(),
                "error", status.getReasonPhrase(),
                "message", ex.getMessage() != null ? ex.getMessage() : "Request error"
        );

        return new ResponseEntity<>(errorBody, headers, statusCode);
    }

    // 2. Handles @Min / @Max validation errors on @RequestParam
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(ConstraintViolationException ex) {
        String details = ex.getConstraintViolations().stream()
                .map(violation -> {
                    String path = violation.getPropertyPath().toString();
                    String paramName = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
                    return paramName + ": " + violation.getMessage();
                })
                .collect(Collectors.joining(", "));

        Map<String, Object> errorBody = Map.of(
                "timestamp", Instant.now().toString(),
                "status", 400,
                "error", "Bad Request",
                "message", details
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorBody);
    }

    // 3. Catch-all fallback for async timeouts and any unexpected server errors
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleAllUncaughtExceptions(Exception ex) {
        Throwable cause = (ex instanceof CompletionException && ex.getCause() != null)
                ? ex.getCause()
                : ex;

        if (cause instanceof TimeoutException) {
            log.warn("Async operation timed out: {}", cause.getMessage());
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(Map.of(
                    "timestamp", Instant.now().toString(),
                    "status", 504,
                    "error", "Gateway Timeout",
                    "message", "The request took too long to process. Please try again."
            ));
        }

        String errorId = UUID.randomUUID().toString().substring(0, 8);
        log.error("Unhandled exception [errorId={}]: {}", errorId, cause.getMessage(), cause);

        Map<String, Object> errorBody = Map.of(
                "timestamp", Instant.now().toString(),
                "status", 500,
                "error", "Internal Server Error",
                "message", "An unexpected error occurred. Reference ID: " + errorId
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorBody);
    }
}


