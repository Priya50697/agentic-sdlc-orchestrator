package com.sdlcagent.orchestrator.agents.codebase;

import com.sdlcagent.orchestrator.agents.base.GateBean;
import com.sdlcagent.orchestrator.agents.base.Json;
import com.sdlcagent.orchestrator.context.CodeIndex;
import com.sdlcagent.orchestrator.context.CodeIndexer;
import com.sdlcagent.orchestrator.engine.FailureInfo;
import com.sdlcagent.orchestrator.engine.FailureKind;
import com.sdlcagent.orchestrator.engine.StageGraph;
import com.sdlcagent.orchestrator.governance.GateContext;
import com.sdlcagent.orchestrator.governance.GateResult;
import com.sdlcagent.orchestrator.workspace.WorkspaceProperties;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** Every file and class named in the impact analysis must exist in the CodeIndex: no hallucinated code. */
@Component
public class ImpactGate implements GateBean {
    private final CodeIndexer indexer;
    private final WorkspaceProperties workspace;

    public ImpactGate(CodeIndexer indexer, WorkspaceProperties workspace) {
        this.indexer = indexer;
        this.workspace = workspace;
    }

    @Override
    public String name() {
        return "impact-valid";
    }

    @Override
    public GateResult evaluate(GateContext ctx) {
        String json = ctx.result() == null ? null : ctx.result().artifacts().get(StageGraph.IMPACT);
        ImpactAnalysis impact = Json.readOrNull(json, ImpactAnalysis.class);
        if (impact == null) return GateResult.fail(FailureInfo.of(FailureKind.TRANSIENT, "impact artifact missing or not valid JSON"));
        List<String> errors = check(impact, indexer.index(workspace.dir()));
        return errors.isEmpty() ? GateResult.pass()
                : GateResult.fail(FailureInfo.of(FailureKind.TRANSIENT, String.join("; ", errors)));
    }

    static List<String> check(ImpactAnalysis impact, CodeIndex index) {
        List<String> errors = new ArrayList<>();
        for (String f : impact.affectedFiles()) {
            if (!index.hasFile(f)) errors.add("affected file does not exist: " + f);
        }
        for (String c : impact.affectedClasses()) {
            if (!index.hasClass(c)) errors.add("affected class does not exist: " + c);
        }
        if (impact.affectedFiles().isEmpty() && impact.affectedClasses().isEmpty() && impact.newComponents().isEmpty()) {
            errors.add("impact analysis names nothing affected or new");
        }
        return errors;
    }
}
