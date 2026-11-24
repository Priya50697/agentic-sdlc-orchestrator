package com.sdlcagent.urlshortener.service;

/**
 * Thrown when a requested custom alias is already in use. Maps to HTTP 409.
 */
public class AliasAlreadyExistsException extends RuntimeException {

    private final String alias;

    public AliasAlreadyExistsException(String alias) {
        super("Alias already in use: " + alias);
        this.alias = alias;
    }

    public String getAlias() {
        return alias;
    }
}
