package com.sdlcagent.orchestrator.engine;

import com.sdlcagent.orchestrator.governance.FileChange;
import java.util.List;
import java.util.Map;

/** What an executor hands back: artifacts to store, file changes to vet, usage for metrics, or a failure. */
public record NodeResult(Map<String, String> artifacts, List<FileChange> changes, Usage usage, FailureInfo failure) {
    public record Usage(int llmCalls, long inputTokens, long outputTokens, double costUsd, boolean cacheHit) {
        public static final Usage NONE = new Usage(0, 0, 0, 0.0, false);
    }

    public NodeResult {
        artifacts = artifacts == null ? Map.of() : artifacts;
        changes = changes == null ? List.of() : changes;
        usage = usage == null ? Usage.NONE : usage;
    }

    public static NodeResult ok(Map<String, String> artifacts) {
        return new NodeResult(artifacts, List.of(), Usage.NONE, null);
    }

    public static NodeResult ok(Map<String, String> artifacts, List<FileChange> changes, Usage usage) {
        return new NodeResult(artifacts, changes, usage, null);
    }

    public static NodeResult failed(FailureInfo failure) {
        return new NodeResult(Map.of(), List.of(), Usage.NONE, failure);
    }
}
