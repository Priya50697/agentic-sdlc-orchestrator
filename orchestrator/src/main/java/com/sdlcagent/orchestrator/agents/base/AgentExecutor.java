package com.sdlcagent.orchestrator.agents.base;

import com.sdlcagent.orchestrator.engine.FailureInfo;
import com.sdlcagent.orchestrator.engine.FailureKind;
import com.sdlcagent.orchestrator.engine.NodeContext;
import com.sdlcagent.orchestrator.engine.NodeExecutor;
import com.sdlcagent.orchestrator.engine.NodeResult;
import com.sdlcagent.orchestrator.llm.LlmException;

/** Adapts an {@link SdlcAgent} to the engine's NodeExecutor and turns every escape (null, exception) into a FailureInfo. */
public record AgentExecutor(SdlcAgent agent) implements NodeExecutor {
    @Override
    public NodeResult execute(NodeContext ctx) {
        try {
            NodeResult r = agent.run(ctx);
            return r != null ? r : NodeResult.failed(FailureInfo.of(FailureKind.OTHER, agent.key() + " returned nothing"));
        } catch (LlmException e) {
            return NodeResult.failed(FailureInfo.of(FailureKind.TRANSIENT, agent.key() + ": " + e.kind() + " " + e.getMessage()));
        } catch (Exception e) {
            return NodeResult.failed(FailureInfo.of(FailureKind.OTHER,
                    agent.key() + " crashed: " + e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }
}
