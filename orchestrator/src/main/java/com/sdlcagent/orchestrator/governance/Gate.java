package com.sdlcagent.orchestrator.governance;

/** Deterministic check run before (entry) or after (exit) a node. No LLM involved. */
@FunctionalInterface
public interface Gate {
    GateResult evaluate(GateContext ctx);
}
