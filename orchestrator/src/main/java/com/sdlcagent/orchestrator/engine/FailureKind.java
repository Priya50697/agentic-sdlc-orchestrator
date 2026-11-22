package com.sdlcagent.orchestrator.engine;

public enum FailureKind {
    /** compile error under src/test */
    COMPILE_TEST,
    /** compile error under src/main */
    COMPILE_MAIN,
    /** non-assertion exception whose top project frame is in src/main */
    EXCEPTION_MAIN,
    ASSERTION,
    /** requirement gap flagged by an agent: needs a human */
    SPEC_GAP,
    /** entry gate / wiring problem: retrying cannot help */
    PRECONDITION,
    /** LLM timeout, process or parse error */
    TRANSIENT,
    POLICY,
    OTHER
}
