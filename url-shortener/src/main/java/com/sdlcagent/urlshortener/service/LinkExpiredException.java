package com.sdlcagent.urlshortener.service;

/**
 * Thrown when an existing link has passed its expiry time. Maps to HTTP 410.
 */
public class LinkExpiredException extends RuntimeException {

    private final String code;

    public LinkExpiredException(String code) {
        super("Link has expired: " + code);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
