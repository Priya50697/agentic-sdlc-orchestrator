package com.sdlcagent.orchestrator.agents.docs;

import com.sdlcagent.orchestrator.agents.base.AbstractLlmAgent;
import com.sdlcagent.orchestrator.agents.base.FileEntry;
import com.sdlcagent.orchestrator.agents.base.Json;
import com.sdlcagent.orchestrator.agents.base.PromptLoader;
import com.sdlcagent.orchestrator.agents.requirements.NormalizedSpec;
import com.sdlcagent.orchestrator.context.CodeIndex;
import com.sdlcagent.orchestrator.context.CodeIndexer;
import com.sdlcagent.orchestrator.engine.NodeContext;
import com.sdlcagent.orchestrator.engine.NodeResult;
import com.sdlcagent.orchestrator.engine.StageGraph;
import com.sdlcagent.orchestrator.governance.FileChange;
import com.sdlcagent.orchestrator.llm.LlmClient;
import com.sdlcagent.orchestrator.workspace.WorkspaceProperties;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Writes README/API docs from the spec and the REAL endpoints in the code index; falls back to a template. */
@Component
public class DocWriterAgent extends AbstractLlmAgent<DocSet> {
    private final CodeIndexer indexer;
    private final WorkspaceProperties workspace;

    public DocWriterAgent(LlmClient llm, PromptLoader prompts, CodeIndexer indexer, WorkspaceProperties workspace) {
        super(llm, prompts, DocSet.class);
        this.indexer = indexer;
        this.workspace = workspace;
    }

    @Override
    public String key() {
        return "doc-writer";
    }

    @Override
    protected String buildUserPrompt(NodeContext ctx) {
        CodeIndex index = indexer.index(workspace.dir());
        return assemble(
                section("SPEC", input(ctx, StageGraph.SPEC)),
                section("ARCHITECTURE PLAN", input(ctx, StageGraph.PLAN)),
                section("ACTUAL ENDPOINTS (document exactly these)", index.describeEndpoints()),
                section("CODE INDEX", index.summary(15_000)));
    }

    @Override
    protected String validate(NodeContext ctx, DocSet set) {
        String problem = FileEntry.firstProblem(set.files());
        if (problem != null) return problem;
        for (FileEntry f : set.files()) {
            String p = f.path().strip().replace('\\', '/');
            if (!(p.equals("README.md") || p.startsWith("docs/")) || !p.endsWith(".md")) {
                return "docs may only write README.md or docs/*.md: " + p;
            }
        }
        FileEntry readme = readme(set);
        if (readme == null) return "README.md is required";
        List<String> missing = DocText.missingSections(readme.content());
        return missing.isEmpty() ? null : "README.md lacks sections: " + missing;
    }

    private static FileEntry readme(DocSet set) {
        return set.files().stream().filter(f -> f.path().strip().equals("README.md")).findFirst().orElse(null);
    }

    @Override
    protected Map<String, String> toArtifacts(DocSet set) {
        String all = set.files().stream().map(FileEntry::content).reduce("", (a, b) -> a + "\n" + b);
        FileEntry readme = readme(set);
        DocReport report = new DocReport(set.summary(), set.files().stream().map(FileEntry::path).toList(),
                DocText.headings(readme == null ? "" : readme.content()),
                List.copyOf(DocText.documentedEndpoints(all)), false);
        return Map.of(StageGraph.DOC_REPORT, Json.write(report));
    }

    @Override
    protected List<FileChange> toChanges(DocSet set) {
        return FileEntry.toChanges(set.files());
    }

    @Override
    protected NodeResult onGiveUp(NodeContext ctx, NodeResult failed) {
        CodeIndex index = indexer.index(workspace.dir());
        String spec = input(ctx, StageGraph.SPEC);
        NormalizedSpec parsed = Json.readOrNull(spec, NormalizedSpec.class);
        String md = DocText.template(parsed == null || parsed.title() == null ? "Service" : parsed.title(), index);
        DocReport report = new DocReport("template fallback: " + failed.failure().message(), List.of("README.md"),
                DocText.headings(md), List.copyOf(DocText.documentedEndpoints(md)), true);
        return NodeResult.ok(Map.of(StageGraph.DOC_REPORT, Json.write(report)),
                List.of(FileChange.create("README.md", md)), failed.usage());
    }
}
