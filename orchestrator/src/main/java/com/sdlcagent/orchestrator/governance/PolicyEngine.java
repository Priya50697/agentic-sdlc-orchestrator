package com.sdlcagent.orchestrator.governance;

import java.util.ArrayList;
import java.util.List;

public class PolicyEngine {
    private final List<Policy> policies;

    public PolicyEngine(List<Policy> policies) {
        this.policies = List.copyOf(policies);
    }

    public static PolicyEngine defaults() {
        return new PolicyEngine(List.of(new Policies.Secrets(), new Policies.ForbiddenApis(),
                Policies.PomAllowlist.defaults(), Policies.ChangeControl.defaults()));
    }

    public List<Violation> evaluate(PolicyInput input) {
        List<Violation> all = new ArrayList<>();
        for (Policy p : policies) all.addAll(p.evaluate(input));
        return all;
    }
}
