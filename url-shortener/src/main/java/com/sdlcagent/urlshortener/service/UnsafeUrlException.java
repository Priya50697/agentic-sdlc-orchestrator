package com.sdlcagent.urlshortener.service;

/**
 * Thrown when a submitted URL is rejected as unsafe (SSRF protection). Mapped to HTTP 400.
 */
public class UnsafeUrlException extends RuntimeException {

    private final String reason;

    public UnsafeUrlException(String reason) {
        super(reason);
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
