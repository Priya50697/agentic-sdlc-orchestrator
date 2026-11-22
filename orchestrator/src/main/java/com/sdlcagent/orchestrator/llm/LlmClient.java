package com.sdlcagent.orchestrator.llm;

/** Single abstraction over every LLM backend (CLI, Spring AI, cache, fake). */
public interface LlmClient {
    LlmResponse complete(LlmRequest request);
}
