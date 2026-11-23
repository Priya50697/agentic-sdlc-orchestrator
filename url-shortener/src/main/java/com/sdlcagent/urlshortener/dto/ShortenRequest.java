package com.sdlcagent.urlshortener.dto;

import com.sdlcagent.urlshortener.validator.ValidUrl;
import jakarta.validation.constraints.NotBlank;

public record ShortenRequest(
        @NotBlank(message = "url must not be blank")
        @ValidUrl
        String url) {
}
