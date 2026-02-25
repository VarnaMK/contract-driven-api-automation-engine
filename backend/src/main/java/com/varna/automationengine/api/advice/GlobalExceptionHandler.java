package com.varna.automationengine.api.advice;

import com.varna.automationengine.domain.exception.AutomationEngineException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import java.time.Instant;
import java.util.UUID;

/**
 * Global exception handler for the entire API layer.
 *
 * <p>{@code @RestControllerAdvice} is a Spring annotation that allows this class
 * to intercept exceptions thrown by ANY controller in the application. Think of it
 * as a safety net — if an exception escapes a controller's own try-catch blocks,
 * it lands here instead of becoming an ugly 500 error with a raw Java stack trace.
 *
 * <p>Why have this in addition to the controller's own try-catch?
 * <ul>
 *   <li>Some exceptions are thrown by Spring BEFORE the controller method is even called
 *       (e.g. file size exceeded, missing required request parts).</li>
 *   <li>Provides a single place for cross-cutting error response formatting.</li>
 *   <li>Keeps individual controllers clean and focused on their happy path.</li>
 * </ul>
 *
 * <p><b>Architecture note:</b> This class is part of the {@code api} (Presentation) layer.
 * It must NEVER import or reference infrastructure or domain implementation classes directly —
 * only domain exception interfaces/abstract classes are permitted.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ─────────────────────────────────────────────────────────────────────────
    // SPRING MULTIPART / FILE UPLOAD EXCEPTIONS
    // These are thrown by Spring's servlet layer before our controller runs.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Handles the case where the uploaded file exceeds the configured maximum size.
     *
     * <p>Spring Boot has a built-in max upload size (configured in application.yml via
     * {@code spring.servlet.multipart.max-file-size}). If the client sends a file larger
     * than that limit, Spring throws this exception before our controller even runs.
     *
     * @param ex      the size exceeded exception
     * @param request the HTTP request that caused the error
     * @return 413 Payload Too Large with a structured error body
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUploadSizeExceeded(
            MaxUploadSizeExceededException ex,
            HttpServletRequest request) {

        String traceId = generateTraceId();
        log.warn("[traceId={}] Upload rejected: file size exceeded Spring's configured max | URI={}",
                traceId, request.getRequestURI());

        return ResponseEntity
                .status(HttpStatus.PAYLOAD_TOO_LARGE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse(
                        "FILE_TOO_LARGE",
                        "The uploaded file exceeds the maximum allowed size. Please check your file and try again.",
                        traceId,
                        Instant.now().toString()
                ));
    }

    /**
     * Handles general multipart form errors — for example, when the required form field
     * "contractFile" is missing entirely from the request.
     *
     * @param ex      the multipart exception
     * @param request the HTTP request that caused the error
     * @return 400 Bad Request with a structured error body
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ErrorResponse> handleMultipartException(
            MultipartException ex,
            HttpServletRequest request) {

        String traceId = generateTraceId();
        log.warn("[traceId={}] Multipart request error | URI={} | message={}",
                traceId, request.getRequestURI(), ex.getMessage());

        return ResponseEntity
                .badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse(
                        "INVALID_REQUEST",
                        "Invalid multipart request. Ensure you are sending a file with the field name 'contractFile'.",
                        traceId,
                        Instant.now().toString()
                ));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DOMAIN EXCEPTIONS
    // These are our own exception hierarchy from the domain layer.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Handles all domain-level exceptions that weren't caught by the controller.
     *
     * <p>{@link AutomationEngineException} is the root of our domain exception hierarchy.
     * By catching it here, we provide a safety net for any domain exception that the
     * controller didn't explicitly handle in its own try-catch.
     *
     * @param ex      the domain exception
     * @param request the HTTP request that caused the error
     * @return 500 Internal Server Error with a structured error body
     */
    @ExceptionHandler(AutomationEngineException.class)
    public ResponseEntity<ErrorResponse> handleDomainException(
            AutomationEngineException ex,
            HttpServletRequest request) {

        String traceId = generateTraceId();
        log.error("[traceId={}] Unhandled domain exception | URI={} | type={}",
                traceId, request.getRequestURI(), ex.getClass().getSimpleName(), ex);

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse(
                        "ENGINE_ERROR",
                        "An internal engine error occurred. Please try again later.",
                        traceId,
                        Instant.now().toString()
                ));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CATCH-ALL
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Final catch-all for any exception not handled by a more specific handler above.
     *
     * <p>This should rarely trigger in production. If it does, it means an exception
     * type we didn't anticipate is escaping. The full stack trace is logged at ERROR
     * level so it's immediately visible in monitoring tools.
     *
     * @param ex      the unexpected exception
     * @param request the HTTP request that caused the error
     * @return 500 Internal Server Error with a safe (no stack trace) error body
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(
            Exception ex,
            HttpServletRequest request) {

        String traceId = generateTraceId();
        // Log with full stack trace (the 'ex' argument at the end does this in SLF4J)
        log.error("[traceId={}] Unhandled exception | URI={}", traceId, request.getRequestURI(), ex);

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse(
                        "INTERNAL_ERROR",
                        "An unexpected error occurred. Use the traceId to locate this error in logs.",
                        traceId,
                        Instant.now().toString()
                ));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Generates a short unique trace ID for correlating a request across log lines.
     *
     * <p>We use the first 8 characters of a UUID to keep it readable in logs
     * while still being unique enough for practical purposes.
     *
     * @return an 8-character alphanumeric trace ID
     */
    private String generateTraceId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // INNER RECORD — ErrorResponse
    // A simple, immutable data carrier for error responses.
    // In a larger project this would live in api/dto/response/ErrorResponse.java
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Represents a structured, client-facing error response body.
     *
     * <p>Using a Java {@code record} (introduced in Java 16) gives us an immutable
     * data class with auto-generated constructor, getters, equals, hashCode, and toString.
     * Spring's Jackson library will automatically serialize this to JSON.
     *
     * <p>Example JSON output:
     * <pre>
     * {
     *   "code": "FILE_TOO_LARGE",
     *   "message": "The uploaded file exceeds the maximum allowed size.",
     *   "traceId": "a3f91bc2",
     *   "timestamp": "2025-01-01T12:00:00Z"
     * }
     * </pre>
     *
     * @param code      a machine-readable error code (stable across releases — safe for clients to switch on)
     * @param message   a human-readable description of what went wrong
     * @param traceId   unique ID to correlate this error with server-side log entries
     * @param timestamp ISO-8601 UTC timestamp of when the error occurred
     */
    public record ErrorResponse(
            String code,
            String message,
            String traceId,
            String timestamp
    ) {}
}