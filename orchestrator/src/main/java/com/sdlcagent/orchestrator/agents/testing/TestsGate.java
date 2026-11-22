package com.sdlcagent.orchestrator.agents.testing;

import com.sdlcagent.orchestrator.agents.base.GateBean;
import com.sdlcagent.orchestrator.agents.base.Json;
import com.sdlcagent.orchestrator.engine.FailureInfo;
import com.sdlcagent.orchestrator.engine.FailureKind;
import com.sdlcagent.orchestrator.engine.StageGraph;
import com.sdlcagent.orchestrator.governance.GateContext;
import com.sdlcagent.orchestrator.governance.GateResult;
import org.springframework.stereotype.Component;

/** Green build and every acceptance criterion backed by at least one passing test. */
@Component
public class TestsGate implements GateBean {
    @Override
    public String name() {
        return "tests-valid";
    }

    @Override
    public GateResult evaluate(GateContext ctx) {
        String json = ctx.result() == null ? null : ctx.result().artifacts().get(StageGraph.TEST_REPORT);
        TestReport r = Json.readOrNull(json, TestReport.class);
        if (r == null) return GateResult.fail(FailureInfo.of(FailureKind.TRANSIENT, "test report missing or invalid"));
        if (!r.allPassed()) {
            return GateResult.fail(FailureInfo.of(FailureKind.OTHER,
                    "tests not green: total=" + r.total() + " failures=" + r.failures() + " errors=" + r.errors()));
        }
        if (!r.uncoveredCriteria().isEmpty()) {
            return GateResult.fail(FailureInfo.of(FailureKind.TRANSIENT,
                    "no passing test for " + r.uncoveredCriteria()
                            + ": name each test with @DisplayName(\"AC-n ...\") for the criterion it verifies"));
        }
        return GateResult.pass();
    }
}
