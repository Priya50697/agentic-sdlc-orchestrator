package com.sdlcagent.orchestrator.agents.base;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Loads {@code prompts/<key>.md} from the classpath (cached). A missing prompt fails fast. */
@Component
public class PromptLoader {
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public String get(String key) {
        return cache.computeIfAbsent(key, this::load);
    }

    private String load(String key) {
        String path = "prompts/" + key + ".md";
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing prompt resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + path, e);
        }
    }
}
