package com.sdlcagent.orchestrator.engine;

import java.util.List;
import java.util.Map;

/** Immutable view handed to a worker: inputs are artifact contents, feedback is the errors/answers to act on. */
public record NodeContext(String runId, String nodeId, String agent, int attempt,
                          Map<String, String> params, Map<String, String> inputs, List<String> feedback) {}
