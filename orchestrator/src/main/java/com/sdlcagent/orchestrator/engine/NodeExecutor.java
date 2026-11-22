package com.sdlcagent.orchestrator.engine;

/** Runs one node attempt on a worker thread. Must not mutate engine state; return everything in the result. */
@FunctionalInterface
public interface NodeExecutor {
    NodeResult execute(NodeContext ctx) throws Exception;
}
