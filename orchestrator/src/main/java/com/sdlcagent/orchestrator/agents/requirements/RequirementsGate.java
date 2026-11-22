package com.sdlcagent.orchestrator.agents.requirements;

import com.sdlcagent.orchestrator.agents.base.GateBean;
import com.sdlcagent.orchestrator.agents.base.Json;
import com.sdlcagent.orchestrator.engine.FailureInfo;
import com.sdlcagent.orchestrator.engine.FailureKind;
import com.sdlcagent.orchestrator.engine.StageGraph;
import com.sdlcagent.orchestrator.governance.ApprovalKind;
import com.sdlcagent.orchestrator.governance.GateContext;
import com.sdlcagent.orchestrator.governance.GateResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Exit gate of the requirements stage: schema-level checks, then Gate 0 (human) when a BLOCKING ambiguity is open.
 * The decision is made here, from the data, never by the LLM.
 */
@Component
public class RequirementsGate implements GateBean {
    private static final Pattern AC_ID = Pattern.compile("AC-\\d+");

    @Override
    public String name() {
        return "requirements-valid";
    }

    @Override
    public GateResult evaluate(GateContext ctx) {
        String json = ctx.result() == null ? null : ctx.result().artifacts().get(StageGraph.SPEC);
        NormalizedSpec spec = Json.readOrNull(json, NormalizedSpec.class);
        if (spec == null) return GateResult.fail(FailureInfo.of(FailureKind.TRANSIENT, "spec artifact missing or not valid JSON"));
        List<String> errors = check(spec);
        if (!errors.isEmpty()) return GateResult.fail(FailureInfo.of(FailureKind.TRANSIENT, String.join("; ", errors)));
        List<NormalizedSpec.Ambiguity> blocking = spec.ambiguities().stream().filter(NormalizedSpec.Ambiguity::blocking).toList();
        if (!blocking.isEmpty()) {
            StringBuilder sb = new StringBuilder("Blocking ambiguities in the requirement. Approve to accept the proposed defaults, "
                    + "or reject with feedback to answer them:\n");
            for (NormalizedSpec.Ambiguity a : blocking) {
                sb.append("- ").append(a.id()).append(": ").append(a.question())
                        .append(" (proposed default: ").append(a.proposedDefault()).append(")\n");
            }
            return GateResult.needsApproval(ApprovalKind.GATE_0, sb.toString());
        }
        return GateResult.pass();
    }

    static List<String> check(NormalizedSpec spec) {
        List<String> errors = new ArrayList<>();
        if (spec.stories().isEmpty()) errors.add("spec needs at least one story");
        if (spec.acceptanceCriteria().isEmpty()) errors.add("spec needs at least one acceptance criterion");
        Set<String> storyIds = new HashSet<>();
        spec.stories().forEach(s -> storyIds.add(s.id()));
        Set<String> seen = new HashSet<>();
        for (NormalizedSpec.Criterion c : spec.acceptanceCriteria()) {
            if (c.id() == null || !AC_ID.matcher(c.id()).matches()) errors.add("criterion id must look like AC-1: " + c.id());
            else if (!seen.add(c.id())) errors.add("duplicate criterion id " + c.id());
            if (c.text() == null || c.text().isBlank()) errors.add("criterion " + c.id() + " has no text");
            else if (c.text().strip().length() < 15) errors.add("criterion " + c.id() + " is too vague to test: '" + c.text() + "'");
            if (c.storyId() != null && !storyIds.contains(c.storyId())) errors.add("criterion " + c.id() + " references unknown story " + c.storyId());
        }
        for (NormalizedSpec.Ambiguity a : spec.ambiguities()) {
            if (a.blocking() && (a.proposedDefault() == null || a.proposedDefault().isBlank())) {
                errors.add("blocking ambiguity " + a.id() + " needs a proposedDefault");
            }
        }
        return errors;
    }
}
