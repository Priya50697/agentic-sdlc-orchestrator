package com.sdlcagent.urlshortener.service;

/**
 * Thrown when a requested custom alias is malformed, has an invalid length or is reserved.
 * Maps to HTTP 400.
 */
public class InvalidAliasException extends RuntimeException {

    public InvalidAliasException(String message) {
        super(message);
    }
}
