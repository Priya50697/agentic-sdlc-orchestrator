package com.sdlcagent.orchestrator.llm;

public class LlmException extends RuntimeException {
    public enum Kind { TIMEOUT, PROCESS, PROTOCOL, UNPARSEABLE, CACHE_MISS }

    private final Kind kind;

    public LlmException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public LlmException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
