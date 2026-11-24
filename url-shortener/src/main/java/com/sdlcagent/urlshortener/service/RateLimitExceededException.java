package com.sdlcagent.urlshortener.service;

/**
 * Thrown when a client IP exceeds the link creation quota. Maps to HTTP 429.
 */
public class RateLimitExceededException extends RuntimeException {

    public RateLimitExceededException() {
        super("Rate limit exceeded. Please try again later.");
    }

    public RateLimitExceededException(String message) {
        super(message);
    }
}
