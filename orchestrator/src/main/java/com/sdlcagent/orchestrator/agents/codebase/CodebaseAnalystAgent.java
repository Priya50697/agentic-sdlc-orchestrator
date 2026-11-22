package com.sdlcagent.orchestrator.agents.codebase;

import com.sdlcagent.orchestrator.agents.base.AbstractLlmAgent;
import com.sdlcagent.orchestrator.agents.base.Json;
import com.sdlcagent.orchestrator.agents.base.PromptLoader;
import com.sdlcagent.orchestrator.context.CodeIndexer;
import com.sdlcagent.orchestrator.engine.NodeContext;
import com.sdlcagent.orchestrator.engine.StageGraph;
import com.sdlcagent.orchestrator.llm.LlmClient;
import com.sdlcagent.orchestrator.workspace.WorkspaceProperties;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class CodebaseAnalystAgent extends AbstractLlmAgent<ImpactAnalysis> {
    private final CodeIndexer indexer;
    private final WorkspaceProperties workspace;

    public CodebaseAnalystAgent(LlmClient llm, PromptLoader prompts, CodeIndexer indexer, WorkspaceProperties workspace) {
        super(llm, prompts, ImpactAnalysis.class);
        this.indexer = indexer;
        this.workspace = workspace;
    }

    @Override
    public String key() {
        return "codebase-analyst";
    }

    @Override
    protected String buildUserPrompt(NodeContext ctx) {
        return assemble(
                section("SPEC", input(ctx, StageGraph.SPEC)),
                section("CODE INDEX (existing code)", indexer.index(workspace.dir()).summary(30_000)));
    }

    @Override
    protected Map<String, String> toArtifacts(ImpactAnalysis result) {
        return Map.of(StageGraph.IMPACT, Json.write(result));
    }
}
