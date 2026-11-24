package com.sdlcagent.urlshortener.validator;

import com.fasterxml.jackson.databind.JsonNode;
import com.sdlcagent.urlshortener.service.InvalidTtlException;
import org.springframework.stereotype.Component;

/**
 * Strictly parses ttlSeconds: missing/null means no TTL; otherwise the value must be
 * an integral JSON number greater than zero that fits in a long.
 */
@Component
public class TtlValidator {

    /**
     * @return the TTL in seconds, or null when no TTL was supplied
     * @throws InvalidTtlException if the value is not a strictly positive integer
     */
    public Long parse(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (!node.isNumber() || !node.isIntegralNumber() || !node.canConvertToLong()) {
            throw new InvalidTtlException("ttlSeconds must be a positive integer");
        }
        long value = node.longValue();
        if (value <= 0) {
            throw new InvalidTtlException("ttlSeconds must be greater than zero");
        }
        return value;
    }
}
