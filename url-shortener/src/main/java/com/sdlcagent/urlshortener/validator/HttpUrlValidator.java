package com.sdlcagent.urlshortener.validator;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.net.URI;
import java.net.URISyntaxException;

public class HttpUrlValidator implements ConstraintValidator<ValidUrl, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return false;
        }
        final URI uri;
        try {
            uri = new URI(value.trim());
        } catch (URISyntaxException e) {
            return false;
        }
        if (!uri.isAbsolute() || uri.isOpaque()) {
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return false;
        }
        String host = uri.getHost();
        return host != null && !host.isBlank();
    }
}
