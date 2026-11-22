package com.sdlcagent.orchestrator.engine;

import java.util.List;
import java.util.Map;

/** Creates nodes at runtime when a node passes (the Architect's TaskGraph becomes task + wave-gate nodes). */
@FunctionalInterface
public interface NodeExpander {
    /** extraDependencies: existing node id -> deps to attach (replaces what an earlier expansion attached). */
    record Expansion(List<Node> nodes, Map<String, List<String>> extraDependencies) {}

    Expansion expand(Node owner, Map<String, String> outputs);
}
