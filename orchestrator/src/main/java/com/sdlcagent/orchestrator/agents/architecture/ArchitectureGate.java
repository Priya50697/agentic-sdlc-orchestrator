package com.sdlcagent.orchestrator.agents.architecture;

import com.sdlcagent.orchestrator.agents.base.GateBean;
import com.sdlcagent.orchestrator.agents.base.Json;
import com.sdlcagent.orchestrator.agents.requirements.NormalizedSpec;
import com.sdlcagent.orchestrator.engine.FailureInfo;
import com.sdlcagent.orchestrator.engine.FailureKind;
import com.sdlcagent.orchestrator.engine.StageGraph;
import com.sdlcagent.orchestrator.engine.TaskGraph;
import com.sdlcagent.orchestrator.governance.GateContext;
import com.sdlcagent.orchestrator.governance.GateResult;
import com.sdlcagent.orchestrator.workspace.WorkspaceProperties;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Deterministic checks on the plan + task graph: schema, acyclic, every task maps to known ACs and every AC is
 * covered, target paths stay inside src/main and the project package, and file names respect the layer rules.
 */
@Component
public class ArchitectureGate implements GateBean {
    private final WorkspaceProperties workspace;

    public ArchitectureGate(WorkspaceProperties workspace) {
        this.workspace = workspace;
    }

    @Override
    public String name() {
        return "architecture-valid";
    }

    @Override
    public GateResult evaluate(GateContext ctx) {
        if (ctx.result() == null) return GateResult.fail(FailureInfo.of(FailureKind.TRANSIENT, "no result to check"));
        String planJson = ctx.result().artifacts().get(StageGraph.PLAN);
        String graphJson = ctx.result().artifacts().get(StageGraph.TASK_GRAPH);
        if (planJson == null || graphJson == null) {
            return GateResult.fail(FailureInfo.of(FailureKind.TRANSIENT, "architecture-plan and task-graph artifacts are both required"));
        }
        TaskGraph graph;
        try {
            graph = TaskGraph.parse(graphJson);
        } catch (IllegalArgumentException e) {
            return GateResult.fail(FailureInfo.of(FailureKind.TRANSIENT, e.getMessage()));
        }
        NormalizedSpec spec = Json.readOrNull(ctx.inputs().get(StageGraph.SPEC), NormalizedSpec.class);
        List<String> errors = check(graph, spec, workspace.projectPackage());
        return errors.isEmpty() ? GateResult.pass()
                : GateResult.fail(FailureInfo.of(FailureKind.TRANSIENT, String.join("; ", errors)));
    }

    static List<String> check(TaskGraph graph, NormalizedSpec spec, String projectPackage) {
        List<String> errors = new ArrayList<>(graph.validate());
        if (!errors.isEmpty()) return errors;
        String pkgPath = projectPackage.replace('.', '/');
        Set<String> specAcs = new HashSet<>();
        if (spec != null) spec.acceptanceCriteria().forEach(c -> specAcs.add(c.id()));
        Set<String> covered = new HashSet<>();
        for (TaskGraph.TaskSpec t : graph.tasks()) {
            if (t.acceptanceCriteria().isEmpty()) errors.add("task " + t.id() + " maps to no acceptance criterion");
            for (String ac : t.acceptanceCriteria()) {
                covered.add(ac);
                if (spec != null && !specAcs.contains(ac)) errors.add("task " + t.id() + " references unknown " + ac);
            }
            if (t.targetPaths().isEmpty()) errors.add("task " + t.id() + " has no targetPaths");
            for (String p : t.targetPaths()) errors.addAll(checkPath(t.id(), p, pkgPath));
        }
        for (String ac : specAcs) {
            if (!covered.contains(ac)) errors.add("acceptance criterion " + ac + " is not covered by any task");
        }
        return errors;
    }

    private static List<String> checkPath(String taskId, String raw, String pkgPath) {
        List<String> errors = new ArrayList<>();
        String p = raw == null ? "" : raw.replace('\\', '/');
        if (p.isBlank() || p.startsWith("/") || p.matches("^[A-Za-z]:.*") || p.contains("..")) {
            errors.add("task " + taskId + " has an unsafe path: " + raw);
            return errors;
        }
        boolean java = p.endsWith(".java");
        if (!p.startsWith("src/main/")) {
            errors.add("task " + taskId + " may only target src/main (tests belong to the test stage): " + p);
        } else if (java && !p.startsWith("src/main/java/" + pkgPath + "/")) {
            errors.add("task " + taskId + " path is outside the project package " + pkgPath + ": " + p);
        }
        if (java) {
            String file = p.substring(p.lastIndexOf('/') + 1);
            String dir = p.substring(0, p.lastIndexOf('/'));
            if (file.endsWith("Controller.java") && !dir.matches(".*/(api|web|controller)(/.*)?")) {
                errors.add("layer rule: controller must live in an api/web/controller package: " + p);
            }
            if (file.endsWith("Repository.java") && !dir.matches(".*/(repository|persistence|store)(/.*)?")) {
                errors.add("layer rule: repository must live in a repository/persistence package: " + p);
            }
            if (file.endsWith("Service.java") && dir.matches(".*/(api|web|controller|repository)(/.*)?")) {
                errors.add("layer rule: service must not live in the api or repository layer: " + p);
            }
        }
        return errors;
    }
}
