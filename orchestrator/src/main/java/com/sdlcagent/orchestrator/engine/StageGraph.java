package com.sdlcagent.orchestrator.engine;

import com.sdlcagent.orchestrator.governance.ApprovalKind;
import java.util.List;

/** The fixed SDLC stage graph. Gate and agent names are looked up in the engine config at runtime. */
public final class StageGraph {
    private StageGraph() {}

    public static final String REQUIREMENT = "requirement";
    public static final String SPEC = "spec";
    public static final String IMPACT = "impact";
    public static final String PLAN = "architecture-plan";
    public static final String TASK_GRAPH = "task-graph";
    public static final String TEST_REPORT = "test-report";
    public static final String DOC_REPORT = "doc-report";
    public static final String RELEASE_REPORT = "release-report";

    public static final String TASK_GRAPH_EXPANDER = "task-graph";

    /**
     * Requirements -> CodebaseAnalysis (SKIPPED when greenfield) -> Architecture [Gate 1, expands into task waves]
     * -> Implementation (join) -> Testing || Docs -> join -> ReleaseReadiness [Gate 2].
     * Gate 0 is raised dynamically by the requirements exit gate when ambiguities block.
     */
    public static List<Node> standard(boolean greenfield) {
        Node requirements = Node.stage("requirements", "requirements").consumes(REQUIREMENT)
                .exit("requirements-valid");
        Node analysis = Node.stage("codebase-analysis", "codebase-analyst").dependsOn("requirements")
                .consumes(SPEC).exit("impact-valid");
        if (greenfield) analysis.state = NodeState.SKIPPED;
        Node architecture = Node.stage("architecture", "architect").dependsOn("requirements", "codebase-analysis")
                .consumes(SPEC, IMPACT).exit("architecture-valid").approval(ApprovalKind.GATE_1)
                .expanding(TASK_GRAPH_EXPANDER);
        Node implementation = Node.join("implementation").dependsOn("architecture");
        Node testing = Node.stage("testing", "test-engineer").dependsOn("implementation")
                .consumes(SPEC, PLAN).exit("tests-valid");
        Node docs = Node.stage("docs", "doc-writer").dependsOn("implementation")
                .consumes(SPEC, PLAN).exit("docs-valid");
        Node release = Node.stage("release", "release-reviewer").dependsOn("testing", "docs")
                .consumes(TEST_REPORT, DOC_REPORT).exit("release-valid").approval(ApprovalKind.GATE_2);
        return List.of(requirements, analysis, architecture, implementation, testing, docs, release);
    }
}
