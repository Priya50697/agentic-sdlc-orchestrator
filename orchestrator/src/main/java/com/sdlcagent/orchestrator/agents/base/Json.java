package com.sdlcagent.orchestrator.agents.base;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/** Shared mapper for artifacts: pretty, tolerant of unknown fields. */
public final class Json {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private Json() {}

    public static String write(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize " + o.getClass().getSimpleName(), e);
        }
    }

    /** Null-safe: returns null when the text is blank or not valid JSON for the type. */
    public static <T> T readOrNull(String json, Class<T> type) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
