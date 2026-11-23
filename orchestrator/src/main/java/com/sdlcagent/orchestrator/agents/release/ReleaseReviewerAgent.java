package com.sdlcagent.orchestrator.agents.release;

import com.sdlcagent.orchestrator.agents.base.AbstractLlmAgent;
import com.sdlcagent.orchestrator.agents.base.Json;
import com.sdlcagent.orchestrator.agents.base.PromptLoader;
import com.sdlcagent.orchestrator.engine.NodeContext;
import com.sdlcagent.orchestrator.engine.NodeResult;
import com.sdlcagent.orchestrator.engine.StageGraph;
import com.sdlcagent.orchestrator.llm.LlmClient;
import com.sdlcagent.orchestrator.persistence.RunPaths;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ReleaseReviewerAgent extends AbstractLlmAgent<ReleaseReview> {
    public static final String REPORT_MD = "release-report.md";

    private final RunPaths paths;

    public ReleaseReviewerAgent(LlmClient llm, PromptLoader prompts, RunPaths paths) {
        super(llm, prompts, ReleaseReview.class);
        this.paths = paths;
    }

    @Override
    public String key() {
        return "release-reviewer";
    }

    @Override
    protected String buildUserPrompt(NodeContext ctx) {
        ReleaseFacts facts = ReleaseFacts.collect(paths.auditFile(ctx.runId()), true);
        return assemble(
                section("TEST REPORT", input(ctx, StageGraph.TEST_REPORT)),
                section("DOC REPORT", input(ctx, StageGraph.DOC_REPORT)),
                section("LIVE ENDPOINT CHECK (advisory)", input(ctx, StageGraph.LIVE_REPORT)),
                section("AUDIT FACTS (authoritative)", Json.write(facts)));
    }

    @Override
    protected String validate(NodeContext ctx, ReleaseReview review) {
        if (review.verdict() == null || !(review.ready() || "NOT_READY".equalsIgnoreCase(review.verdict()))) {
            return "verdict must be READY or NOT_READY";
        }
        return review.summary() == null || review.summary().isBlank() ? "summary is required" : null;
    }

    @Override
    protected Map<String, String> toArtifacts(ReleaseReview review) {
        throw new UnsupportedOperationException("built in finish(): needs the run id");
    }

    @Override
    protected NodeResult finish(NodeContext ctx, ReleaseReview review, NodeResult.Usage usage) {
        ReleaseFacts facts = ReleaseFacts.collect(paths.auditFile(ctx.runId()), true);
        ReleaseReadinessReport report = new ReleaseReadinessReport(review.verdict().toUpperCase(), review.summary(),
                review.risks(), review.followUps(), facts);
        Map<String, String> out = new LinkedHashMap<>();
        out.put(StageGraph.RELEASE_REPORT, Json.write(report));
        out.put(REPORT_MD, report.toMarkdown());
        return NodeResult.ok(out, java.util.List.of(), usage);
    }
}
