package com.sdlcagent.orchestrator.agents.release;

import com.sdlcagent.orchestrator.agents.base.GateBean;
import com.sdlcagent.orchestrator.agents.base.Json;
import com.sdlcagent.orchestrator.engine.FailureInfo;
import com.sdlcagent.orchestrator.engine.FailureKind;
import com.sdlcagent.orchestrator.engine.StageGraph;
import com.sdlcagent.orchestrator.governance.GateContext;
import com.sdlcagent.orchestrator.governance.GateResult;
import com.sdlcagent.orchestrator.persistence.RunPaths;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** Re-derives the facts from the audit log: the LLM's report cannot talk the gate into passing. Gate 2 follows. */
@Component
public class ReleaseGate implements GateBean {
    private final RunPaths paths;

    public ReleaseGate(RunPaths paths) {
        this.paths = paths;
    }

    @Override
    public String name() {
        return "release-valid";
    }

    @Override
    public GateResult evaluate(GateContext ctx) {
        String json = ctx.result() == null ? null : ctx.result().artifacts().get(StageGraph.RELEASE_REPORT);
        ReleaseReadinessReport report = Json.readOrNull(json, ReleaseReadinessReport.class);
        if (report == null) return GateResult.fail(FailureInfo.of(FailureKind.TRANSIENT, "release report missing or invalid"));
        ReleaseFacts facts = ReleaseFacts.collect(paths.auditFile(ctx.runId()), true);
        List<String> errors = check(facts, report.verdict());
        return errors.isEmpty() ? GateResult.pass() : GateResult.fail(FailureInfo.of(FailureKind.OTHER, String.join("; ", errors)));
    }

    static List<String> check(ReleaseFacts facts, String verdict) {
        List<String> errors = new ArrayList<>();
        if (!facts.auditChainValid()) errors.add("audit chain failed verification");
        if (facts.criticalViolations() > 0) errors.add(facts.criticalViolations() + " critical policy violation(s) on record");
        if (!facts.missingStages().isEmpty()) errors.add("stages without a recorded pass (gate bypassed?): " + facts.missingStages());
        if (facts.metrics() == null) errors.add("metrics missing");
        if (!"READY".equalsIgnoreCase(verdict)) errors.add("reviewer verdict is " + verdict);
        return errors;
    }
}
