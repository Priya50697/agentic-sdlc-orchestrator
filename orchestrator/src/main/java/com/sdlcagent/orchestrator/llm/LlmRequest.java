package com.sdlcagent.orchestrator.llm;

import java.time.Duration;

/** model may be null (use the client default); timeout may be null (use the client default). */
public record LlmRequest(String system, String user, String model, Duration timeout) {
    public LlmRequest {
        if (user == null || user.isBlank()) throw new IllegalArgumentException("user prompt is required");
        system = system == null ? "" : system;
    }

    public static LlmRequest of(String system, String user) {
        return new LlmRequest(system, user, null, null);
    }
}
