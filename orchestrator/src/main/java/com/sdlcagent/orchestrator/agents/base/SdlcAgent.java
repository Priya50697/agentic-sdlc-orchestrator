package com.sdlcagent.orchestrator.agents.base;

import com.sdlcagent.orchestrator.engine.NodeContext;
import com.sdlcagent.orchestrator.engine.NodeResult;

/**
 * One stage worker, registered under {@link #key()} (the {@code Node.agent} value). Implementations must not
 * mutate engine state or write the workspace; they return artifacts and proposed file changes.
 */
public interface SdlcAgent {
    String key();

    NodeResult run(NodeContext ctx) throws Exception;
}
