package com.sdlcagent.orchestrator.agents.implementation;

import com.sdlcagent.orchestrator.agents.base.OwnerResolver;
import com.sdlcagent.orchestrator.agents.base.SdlcAgent;
import com.sdlcagent.orchestrator.engine.ArtifactStore;
import com.sdlcagent.orchestrator.engine.FailureInfo;
import com.sdlcagent.orchestrator.engine.NodeContext;
import com.sdlcagent.orchestrator.engine.NodeResult;
import com.sdlcagent.orchestrator.engine.StageGraph;
import com.sdlcagent.orchestrator.persistence.RunPaths;
import com.sdlcagent.orchestrator.workspace.MavenRunner;
import com.sdlcagent.orchestrator.workspace.SurefireParser;
import com.sdlcagent.orchestrator.workspace.WorkspaceProperties;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Non-LLM wave gate: compiles the workspace; a compile error routes back to the task that owns the file. */
@Component
public class CompileGateExecutor implements SdlcAgent {
    private final MavenRunner maven;
    private final RunPaths paths;
    private final WorkspaceProperties workspace;

    public CompileGateExecutor(MavenRunner maven, RunPaths paths, WorkspaceProperties workspace) {
        this.maven = maven;
        this.paths = paths;
        this.workspace = workspace;
    }

    @Override
    public String key() {
        return "compile-gate";
    }

    @Override
    public NodeResult run(NodeContext ctx) {
        String graph = new ArtifactStore(paths, ctx.runId()).get(StageGraph.TASK_GRAPH).orElse("");
        MavenRunner.Result run = maven.compile();
        FailureInfo failure = SurefireParser.classify(run,
                new SurefireParser.TestSummary(0, 0, 0, 0, List.of()), workspace.projectPackage(), OwnerResolver.from(graph));
        return failure == null ? NodeResult.ok(Map.of()) : NodeResult.failed(failure);
    }
}
