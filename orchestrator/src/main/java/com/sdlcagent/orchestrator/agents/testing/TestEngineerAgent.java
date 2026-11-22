package com.sdlcagent.orchestrator.agents.testing;

import com.sdlcagent.orchestrator.agents.base.AbstractLlmAgent;
import com.sdlcagent.orchestrator.agents.base.FileEntry;
import com.sdlcagent.orchestrator.agents.base.Json;
import com.sdlcagent.orchestrator.agents.base.OwnerResolver;
import com.sdlcagent.orchestrator.agents.base.PromptLoader;
import com.sdlcagent.orchestrator.agents.requirements.NormalizedSpec;
import com.sdlcagent.orchestrator.context.CodeIndexer;
import com.sdlcagent.orchestrator.engine.ArtifactStore;
import com.sdlcagent.orchestrator.engine.FailureInfo;
import com.sdlcagent.orchestrator.engine.NodeContext;
import com.sdlcagent.orchestrator.engine.NodeResult;
import com.sdlcagent.orchestrator.engine.StageGraph;
import com.sdlcagent.orchestrator.governance.FileChange;
import com.sdlcagent.orchestrator.llm.LlmClient;
import com.sdlcagent.orchestrator.persistence.RunPaths;
import com.sdlcagent.orchestrator.workspace.MavenRunner;
import com.sdlcagent.orchestrator.workspace.SandboxedFileWriter;
import com.sdlcagent.orchestrator.workspace.SurefireParser;
import com.sdlcagent.orchestrator.workspace.WorkspaceProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * LLM writes the tests; this class then runs them in a scratch copy of the workspace (nothing is accepted into the
 * real workspace before the gates pass) and reports measured results and AC coverage. A compile or test failure is
 * classified so the engine can route it back to the implementation task that owns the failing code.
 */
@Component
public class TestEngineerAgent extends AbstractLlmAgent<TestSuite> {
    private final CodeIndexer indexer;
    private final WorkspaceProperties workspace;
    private final RunPaths paths;
    private final MavenRunner maven;

    public TestEngineerAgent(LlmClient llm, PromptLoader prompts, CodeIndexer indexer, WorkspaceProperties workspace,
                             RunPaths paths, MavenRunner maven) {
        super(llm, prompts, TestSuite.class);
        this.maven = maven;
        this.indexer = indexer;
        this.workspace = workspace;
        this.paths = paths;
    }

    @Override
    public String key() {
        return "test-engineer";
    }

    @Override
    protected String buildUserPrompt(NodeContext ctx) {
        return assemble(
                section("SPEC (cover every AC-n)", input(ctx, StageGraph.SPEC)),
                section("ARCHITECTURE PLAN", input(ctx, StageGraph.PLAN)),
                section("IMPLEMENTED CODE (index)", indexer.index(workspace.dir()).summary(25_000)),
                section("PROJECT PACKAGE", workspace.projectPackage()));
    }

    @Override
    protected String validate(NodeContext ctx, TestSuite suite) {
        String problem = FileEntry.firstProblem(suite.files());
        if (problem != null) return problem;
        for (FileEntry f : suite.files()) {
            String p = f.path().strip().replace('\\', '/');
            if (!p.startsWith("src/test/")) return "tests must live under src/test/: " + p;
            if (f.toChange().op() == FileChange.Op.DELETE) return "deleting tests is not allowed: " + p;
        }
        return null;
    }

    @Override
    protected Map<String, String> toArtifacts(TestSuite suite) {
        return Map.of();
    }

    @Override
    protected NodeResult finish(NodeContext ctx, TestSuite suite, NodeResult.Usage usage) {
        List<FileChange> changes = FileEntry.toChanges(suite.files());
        Path scratch = null;
        try {
            scratch = Files.createTempDirectory("sdlc-tests-");
            WorkspaceCopy.copy(workspace.dir(), scratch);
            new SandboxedFileWriter(scratch).apply(ctx.runId(), ctx.nodeId(), changes);
            MavenRunner.Result run = maven.withWorkspace(scratch).test();
            SurefireParser.TestSummary summary = SurefireParser.parseReports(scratch);
            ArtifactStore store = new ArtifactStore(paths, ctx.runId());
            String graph = store.get(StageGraph.TASK_GRAPH).orElse("");
            FailureInfo failure = SurefireParser.classify(run, summary, workspace.projectPackage(), OwnerResolver.from(graph));
            if (failure != null) return new NodeResult(Map.of(), List.of(), usage, failure);
            NormalizedSpec spec = Json.readOrNull(input(ctx, StageGraph.SPEC), NormalizedSpec.class);
            TestReport report = buildReport(suite, summary, spec);
            return NodeResult.ok(Map.of(StageGraph.TEST_REPORT, Json.write(report)), changes, usage);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            if (scratch != null) WorkspaceCopy.delete(scratch);
        }
    }

    static TestReport buildReport(TestSuite suite, SurefireParser.TestSummary s, NormalizedSpec spec) {
        List<TestReport.AcCoverage> coverage = new ArrayList<>();
        List<String> uncovered = new ArrayList<>();
        if (spec != null) {
            for (NormalizedSpec.Criterion c : spec.acceptanceCriteria()) {
                List<String> passing = s.cases().stream()
                        .filter(t -> t.status() == SurefireParser.Status.PASSED)
                        .filter(t -> mentions(t.name(), c.id()) || mentions(t.className(), c.id()))
                        .map(SurefireParser.TestCase::id).toList();
                coverage.add(new TestReport.AcCoverage(c.id(), passing));
                if (passing.isEmpty()) uncovered.add(c.id());
            }
        }
        return new TestReport(suite.summary(), s.total(), s.failures(), s.errors(), s.skipped(), coverage, uncovered);
    }

    /** "AC-1" must not match "AC-10". */
    static boolean mentions(String text, String acId) {
        return text != null && java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(acId) + "(?!\\d)")
                .matcher(text).find();
    }
}
