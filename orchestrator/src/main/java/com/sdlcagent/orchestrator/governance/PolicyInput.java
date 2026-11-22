package com.sdlcagent.orchestrator.governance;

import java.util.List;
import java.util.Map;

public record PolicyInput(String nodeId, List<FileChange> changes, Map<String, String> artifacts) {}
