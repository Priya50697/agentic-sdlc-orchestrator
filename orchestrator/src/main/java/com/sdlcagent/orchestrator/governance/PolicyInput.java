package com.sdlcagent.orchestrator.governance;

import java.util.List;
import java.util.Map;

/** agent is the Node.agent key that produced the result (null in tests that do not care). */
public record PolicyInput(String nodeId, String agent, List<FileChange> changes, Map<String, String> artifacts) {
    public PolicyInput(String nodeId, List<FileChange> changes, Map<String, String> artifacts) {
        this(nodeId, null, changes, artifacts);
    }
}
