package com.sdlcagent.orchestrator.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** The three demo requirements, stored as classpath resources under /scenarios/. */
public final class Scenarios {
    private Scenarios() {}

    public static final Set<String> NAMES = Set.of("greenfield", "brownfield", "ambiguous");

    public static boolean isGreenfield(String name) {
        return "greenfield".equals(name);
    }

    public static String requirement(String name) {
        if (name == null || !NAMES.contains(name)) {
            throw new IllegalArgumentException("unknown scenario: " + name + " (known: " + NAMES + ")");
        }
        try (InputStream in = Scenarios.class.getResourceAsStream("/scenarios/" + name + ".md")) {
            if (in == null) throw new IllegalStateException("missing scenario resource " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
