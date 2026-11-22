package com.sdlcagent.orchestrator.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Extracts the JSON payload from an LLM reply (handles ```json fences and surrounding prose). */
public class JsonResponseParser {
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public <T> T parse(String text, Class<T> type) {
        String json = extractJson(text);
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new LlmException(LlmException.Kind.UNPARSEABLE,
                    "cannot map reply to " + type.getSimpleName() + ": " + e.getOriginalMessage(), e);
        }
    }

    static String extractJson(String text) {
        if (text == null) throw new LlmException(LlmException.Kind.UNPARSEABLE, "empty reply");
        String s = text.strip();
        int fence = s.indexOf("```");
        if (fence >= 0) {
            int start = s.indexOf('\n', fence);
            int end = start < 0 ? -1 : s.indexOf("```", start);
            if (start >= 0 && end > start) s = s.substring(start + 1, end).strip();
        }
        int open = firstOf(s, '{', '[');
        if (open < 0) throw new LlmException(LlmException.Kind.UNPARSEABLE, "no JSON found in reply");
        char closer = s.charAt(open) == '{' ? '}' : ']';
        int close = s.lastIndexOf(closer);
        if (close < open) throw new LlmException(LlmException.Kind.UNPARSEABLE, "unterminated JSON in reply");
        return s.substring(open, close + 1);
    }

    private static int firstOf(String s, char a, char b) {
        int i = s.indexOf(a);
        int j = s.indexOf(b);
        if (i < 0) return j;
        if (j < 0) return i;
        return Math.min(i, j);
    }
}
