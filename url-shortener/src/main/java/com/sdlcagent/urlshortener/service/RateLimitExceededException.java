package com.sdlcagent.urlshortener.service;

/**
 * Thrown when a client IP exceeds the request quota. Maps to HTTP 429 with a Retry-After header.
 */
public class RateLimitExceededException extends RuntimeException {

    private static final long DEFAULT_RETRY_AFTER_SECONDS = 60L;

    private final long retryAfterSeconds;

    public RateLimitExceededException() {
        this(DEFAULT_RETRY_AFTER_SECONDS);
    }

    public RateLimitExceededException(String message) {
        super(message);
        this.retryAfterSeconds = DEFAULT_RETRY_AFTER_SECONDS;
    }

    public RateLimitExceededException(long retryAfterSeconds) {
        super("Rate limit exceeded. Please try again later.");
        this.retryAfterSeconds = Math.max(1L, retryAfterSeconds);
    }

    public RateLimitExceededException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = Math.max(1L, retryAfterSeconds);
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
