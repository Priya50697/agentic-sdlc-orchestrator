package com.sdlcagent.orchestrator.agents.base;

import com.sdlcagent.orchestrator.engine.EngineConfig;
import com.sdlcagent.orchestrator.governance.AgentPermissions;
import com.sdlcagent.orchestrator.governance.Policies;
import com.sdlcagent.orchestrator.service.EngineConfigCustomizer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Collects every agent, gate and expander bean into one customizer: adding an agent package needs no central edit. */
@Component
@org.springframework.core.annotation.Order(0) // applied first, so test fakes (default order) can override agents
public class AgentRegistrar implements EngineConfigCustomizer {
    private final List<SdlcAgent> agents;
    private final List<GateBean> gates;
    private final List<ExpanderBean> expanders;

    public AgentRegistrar(List<SdlcAgent> agents, List<GateBean> gates, List<ExpanderBean> expanders) {
        this.agents = agents;
        this.gates = gates;
        this.expanders = expanders;
        requireUnique(agents.stream().map(SdlcAgent::key).toList(), "agent");
        requireUnique(gates.stream().map(GateBean::name).toList(), "gate");
        requireUnique(expanders.stream().map(ExpanderBean::name).toList(), "expander");
    }

    private static void requireUnique(List<String> names, String what) {
        Set<String> seen = new HashSet<>();
        for (String n : names) {
            if (!seen.add(n)) throw new IllegalStateException("duplicate " + what + " registered: " + n);
        }
    }

    @Override
    public void customize(String runId, EngineConfig config) {
        config.policyEngine = config.policyEngine.with(new Policies.AgentScope(AgentPermissions.defaults()));
        agents.forEach(a -> config.executor(a.key(), new AgentExecutor(a)));
        gates.forEach(g -> config.gate(g.name(), g));
        expanders.forEach(e -> config.expander(e.name(), e));
    }
}
