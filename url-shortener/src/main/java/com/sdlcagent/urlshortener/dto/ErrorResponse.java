package com.sdlcagent.urlshortener.dto;

public record ErrorResponse(int status, String error, String message) {
}
