package com.sdlcagent.urlshortener.service;

/**
 * Thrown when ttlSeconds is zero, negative, fractional or non-numeric.
 * Maps to HTTP 400.
 */
public class InvalidTtlException extends RuntimeException {

    public InvalidTtlException(String message) {
        super(message);
    }
}
