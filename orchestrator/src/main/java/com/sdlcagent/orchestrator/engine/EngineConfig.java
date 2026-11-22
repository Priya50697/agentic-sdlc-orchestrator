package com.sdlcagent.orchestrator.engine;

import com.sdlcagent.orchestrator.governance.Gate;
import com.sdlcagent.orchestrator.governance.PolicyEngine;
import com.sdlcagent.orchestrator.governance.RiskClassifier;
import java.util.HashMap;
import java.util.Map;

/** Everything pluggable about an engine instance, with safe defaults. */
public class EngineConfig {
    public record Budget(int maxLlmCalls, int maxBackEdges) {
        public static Budget defaults() {
            return new Budget(60, 8);
        }
    }

    public final Map<String, NodeExecutor> executors = new HashMap<>();
    public final Map<String, Gate> gates = new HashMap<>();
    public final Map<String, NodeExpander> expanders = new HashMap<>();
    public PolicyEngine policyEngine = PolicyEngine.defaults();
    public RiskClassifier riskClassifier = new RiskClassifier();
    public FailureRouter router = new FailureRouter();
    public CheckpointService checkpoints = CheckpointService.NOOP;
    public ChangeApplier changeApplier = ChangeApplier.NOOP;
    public Budget budget = Budget.defaults();

    public EngineConfig executor(String agent, NodeExecutor e) {
        executors.put(agent, e);
        return this;
    }

    public EngineConfig gate(String name, Gate g) {
        gates.put(name, g);
        return this;
    }

    public EngineConfig expander(String name, NodeExpander e) {
        expanders.put(name, e);
        return this;
    }

    public EngineConfig budget(int maxLlmCalls, int maxBackEdges) {
        this.budget = new Budget(maxLlmCalls, maxBackEdges);
        return this;
    }
}
