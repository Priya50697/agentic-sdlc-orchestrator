package com.sdlcagent.orchestrator.governance;

import com.sdlcagent.orchestrator.engine.NodeResult;
import java.util.Map;

/** result is null for entry gates (nothing has run yet). */
public record GateContext(String runId, String nodeId, int attempt, Map<String, String> inputs, NodeResult result) {}
