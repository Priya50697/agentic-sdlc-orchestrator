package com.sdlcagent.urlshortener.validator;

import com.sdlcagent.urlshortener.service.InvalidAliasException;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates custom aliases: pattern ^[A-Za-z0-9_-]+$, length 3-32, not a reserved name.
 * Comparison is case-sensitive.
 */
@Component
public class AliasValidator {

    public static final int MIN_LENGTH = 3;
    public static final int MAX_LENGTH = 32;

    private static final Pattern URL_SAFE = Pattern.compile("^[A-Za-z0-9_-]+$");

    private static final Set<String> RESERVED = Set.of(
            "shorten", "api", "error", "h2-console", "actuator", "static", "admin");

    /**
     * Validates the alias. A null alias means none was requested and is accepted.
     *
     * @throws InvalidAliasException if the alias is invalid
     */
    public void validate(String alias) {
        if (alias == null) {
            return;
        }
        int length = alias.length();
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            throw new InvalidAliasException(
                    "Alias length must be between " + MIN_LENGTH + " and " + MAX_LENGTH + " characters");
        }
        if (!URL_SAFE.matcher(alias).matches()) {
            throw new InvalidAliasException(
                    "Alias may only contain letters, digits, '_' and '-'");
        }
        if (RESERVED.contains(alias)) {
            throw new InvalidAliasException("Alias is reserved");
        }
    }
}
