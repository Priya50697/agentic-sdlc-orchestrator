package com.sdlcagent.orchestrator.llm;

public record LlmResponse(String text, String model, long inputTokens, long outputTokens,
                          double costUsd, long durationMs, boolean cacheHit) {

    public LlmResponse asCacheHit() {
        return new LlmResponse(text, model, inputTokens, outputTokens, 0.0, 0, true);
    }
}
