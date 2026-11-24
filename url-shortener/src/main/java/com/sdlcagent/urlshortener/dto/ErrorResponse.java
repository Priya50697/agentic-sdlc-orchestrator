package com.sdlcagent.urlshortener.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(int status, String error, String message, String code) {

    public ErrorResponse(int status, String error, String message) {
        this(status, error, message, null);
    }
}
