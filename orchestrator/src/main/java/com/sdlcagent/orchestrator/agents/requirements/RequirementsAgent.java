package com.sdlcagent.orchestrator.agents.requirements;

import com.sdlcagent.orchestrator.agents.base.AbstractLlmAgent;
import com.sdlcagent.orchestrator.agents.base.Json;
import com.sdlcagent.orchestrator.agents.base.PromptLoader;
import com.sdlcagent.orchestrator.engine.NodeContext;
import com.sdlcagent.orchestrator.engine.StageGraph;
import com.sdlcagent.orchestrator.llm.LlmClient;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class RequirementsAgent extends AbstractLlmAgent<NormalizedSpec> {
    public RequirementsAgent(LlmClient llm, PromptLoader prompts) {
        super(llm, prompts, NormalizedSpec.class);
    }

    @Override
    public String key() {
        return "requirements";
    }

    @Override
    protected String buildUserPrompt(NodeContext ctx) {
        return assemble(section("REQUIREMENT", input(ctx, StageGraph.REQUIREMENT)));
    }

    @Override
    protected Map<String, String> toArtifacts(NormalizedSpec spec) {
        return Map.of(StageGraph.SPEC, Json.write(spec));
    }
}
