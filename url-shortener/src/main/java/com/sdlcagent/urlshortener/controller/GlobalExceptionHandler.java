package com.sdlcagent.urlshortener.controller;

import com.sdlcagent.urlshortener.dto.ErrorResponse;
import com.sdlcagent.urlshortener.service.AliasAlreadyExistsException;
import com.sdlcagent.urlshortener.service.CodeNotFoundException;
import com.sdlcagent.urlshortener.service.ExternalCheckUnavailableException;
import com.sdlcagent.urlshortener.service.InvalidAliasException;
import com.sdlcagent.urlshortener.service.InvalidTtlException;
import com.sdlcagent.urlshortener.service.LinkExpiredException;
import com.sdlcagent.urlshortener.service.RateLimitExceededException;
import com.sdlcagent.urlshortener.service.UnsafeUrlException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .distinct()
                .collect(Collectors.joining("; "));
        if (message.isBlank()) {
            message = "Validation failed";
        }
        return build(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        return build(HttpStatus.BAD_REQUEST, "Malformed or missing request body");
    }

    @ExceptionHandler(InvalidAliasException.class)
    public ResponseEntity<ErrorResponse> handleInvalidAlias(InvalidAliasException ex) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage() != null ? ex.getMessage() : "Invalid alias");
    }

    @ExceptionHandler(InvalidTtlException.class)
    public ResponseEntity<ErrorResponse> handleInvalidTtl(InvalidTtlException ex) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage() != null ? ex.getMessage() : "Invalid ttlSeconds");
    }

    @ExceptionHandler(UnsafeUrlException.class)
    public ResponseEntity<ErrorResponse> handleUnsafeUrl(UnsafeUrlException ex) {
        String reason = ex.getReason() != null && !ex.getReason().isBlank()
                ? ex.getReason() : "URL is not allowed";
        return build(HttpStatus.BAD_REQUEST, "Unsafe URL rejected: " + reason, "UNSAFE_URL");
    }

    @ExceptionHandler(ExternalCheckUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleExternalCheckUnavailable(ExternalCheckUnavailableException ex) {
        log.warn("External check unavailable");
        return build(HttpStatus.SERVICE_UNAVAILABLE,
                "External URL check is currently unavailable, please try again later",
                "EXTERNAL_CHECK_UNAVAILABLE");
    }

    @ExceptionHandler(AliasAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleAliasExists(AliasAlreadyExistsException ex) {
        return build(HttpStatus.CONFLICT, "Alias already in use");
    }

    @ExceptionHandler(LinkExpiredException.class)
    public ResponseEntity<ErrorResponse> handleExpired(LinkExpiredException ex) {
        return build(HttpStatus.GONE, "Short link has expired");
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleRateLimit(RateLimitExceededException ex) {
        long retryAfter = Math.max(1L, ex.getRetryAfterSeconds());
        HttpStatus status = HttpStatus.TOO_MANY_REQUESTS;
        ErrorResponse body = new ErrorResponse(status.value(), status.getReasonPhrase(),
                "Rate limit exceeded, try again later", "RATE_LIMIT_EXCEEDED");
        return ResponseEntity.status(status)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter))
                .body(body);
    }

    @ExceptionHandler(CodeNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(CodeNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "Short code not found");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "Resource not found");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        log.error("Unexpected error", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message) {
        return build(status, message, null);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message, String code) {
        ErrorResponse body = new ErrorResponse(status.value(), status.getReasonPhrase(), message, code);
        return ResponseEntity.status(status).body(body);
    }
}
