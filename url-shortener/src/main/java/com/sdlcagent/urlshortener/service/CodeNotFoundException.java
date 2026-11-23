package com.sdlcagent.urlshortener.service;

public class CodeNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CodeNotFoundException(String code) {
        super("Code not found: " + code);
    }
}
