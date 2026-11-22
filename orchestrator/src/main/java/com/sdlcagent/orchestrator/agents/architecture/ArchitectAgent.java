package com.sdlcagent.orchestrator.agents.architecture;

import com.sdlcagent.orchestrator.agents.base.AbstractLlmAgent;
import com.sdlcagent.orchestrator.agents.base.Json;
import com.sdlcagent.orchestrator.agents.base.PromptLoader;
import com.sdlcagent.orchestrator.engine.NodeContext;
import com.sdlcagent.orchestrator.engine.StageGraph;
import com.sdlcagent.orchestrator.llm.LlmClient;
import com.sdlcagent.orchestrator.workspace.WorkspaceProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ArchitectAgent extends AbstractLlmAgent<ArchitecturePlan> {
    private final WorkspaceProperties workspace;

    public ArchitectAgent(LlmClient llm, PromptLoader prompts, WorkspaceProperties workspace) {
        super(llm, prompts, ArchitecturePlan.class);
        this.workspace = workspace;
    }

    @Override
    public String key() {
        return "architect";
    }

    @Override
    protected String buildUserPrompt(NodeContext ctx) {
        return assemble(
                section("SPEC", input(ctx, StageGraph.SPEC)),
                section("IMPACT ANALYSIS (absent for greenfield)", input(ctx, StageGraph.IMPACT)),
                section("PROJECT PACKAGE", workspace.projectPackage()));
    }

    @Override
    protected Map<String, String> toArtifacts(ArchitecturePlan plan) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put(StageGraph.PLAN, Json.write(plan.withoutTasks()));
        out.put(StageGraph.TASK_GRAPH, Json.write(Map.of("tasks", plan.tasks())));
        return out;
    }
}
