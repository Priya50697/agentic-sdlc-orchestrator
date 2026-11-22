package com.sdlcagent.orchestrator.service;

import com.sdlcagent.orchestrator.engine.EngineConfig;

/**
 * Extension point: P4 registers the agents, gates and expanders by exposing beans of this type; tests register
 * fakes. Applied in bean order to every new engine, after the workspace wiring (checkpoints, change applier).
 */
@FunctionalInterface
public interface EngineConfigCustomizer {
    void customize(String runId, EngineConfig config);
}
