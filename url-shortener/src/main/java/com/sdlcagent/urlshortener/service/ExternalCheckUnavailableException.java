package com.sdlcagent.urlshortener.service;

/**
 * Thrown when the external URL check still fails after bounded retries. Mapped to HTTP 503.
 */
public class ExternalCheckUnavailableException extends RuntimeException {

    public ExternalCheckUnavailableException(String message) {
        super(message);
    }

    public ExternalCheckUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
