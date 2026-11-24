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
import com.sdlcagent.orchestrator.workspace.TestInventory;
import com.sdlcagent.orchestrator.workspace.WorkspaceProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
        Map<String, String> baseline = baselineMethods(ctx.runId());
        return assemble(
                section("SPEC (cover every AC-n)", input(ctx, StageGraph.SPEC)),
                section("ARCHITECTURE PLAN", input(ctx, StageGraph.PLAN)),
                section("IMPLEMENTED CODE (index)", indexer.index(workspace.dir()).summary(25_000)),
                section("BASELINE TESTS (existed before this run; must all remain, changing one needs supersededTests)",
                        baseline.isEmpty() ? "(none)" : String.join("\n", baseline.keySet())),
                section("EXISTING TEST SOURCE NAMED IN FEEDBACK (edit these with exact-match edits)",
                        sourcesNamedIn(ctx.feedback())),
                section("PROJECT PACKAGE", workspace.projectPackage()));
    }

    private static final Pattern TEST_REF = Pattern.compile("([A-Za-z0-9_$.]+)#[A-Za-z0-9_]+");

    /** Full text of the existing test files whose classes appear in the feedback, so edits can quote them exactly. */
    private String sourcesNamedIn(List<String> feedback) {
        Set<String> classes = new LinkedHashSet<>();
        for (String f : feedback) {
            Matcher m = TEST_REF.matcher(f);
            while (m.find()) classes.add(m.group(1).substring(m.group(1).lastIndexOf('.') + 1));
        }
        if (classes.isEmpty()) return "(none)";
        StringBuilder sb = new StringBuilder();
        TestInventory.readTestSources(workspace.dir()).forEach((path, src) -> {
            String stem = path.substring(path.lastIndexOf('/') + 1).replace(".java", "");
            if (classes.contains(stem) && sb.length() < 30_000) sb.append("--- ").append(path).append(" ---\n").append(src).append("\n");
        });
        return sb.isEmpty() ? "(none)" : sb.toString();
    }

    private Map<String, String> baselineMethods(String runId) {
        Path dir = paths.runDir(runId).resolve(TestInventory.BASELINE_DIR);
        return Files.isDirectory(dir) ? TestInventory.methods(dir) : Map.of();
    }

    /** Result of turning the LLM's files + edits into file changes; exactly one of changes/problem is set. */
    private record Resolved(List<FileChange> changes, String problem) {}

    /**
     * Applies whole files and exact-match edits to the current test sources in memory, then checks the baseline
     * rule. An overwrite of an existing file is a MODIFY (so the risk classifier asks a human), never a silent CREATE.
     */
    private Resolved resolve(NodeContext ctx, TestSuite suite) {
        if (suite.files().isEmpty() && suite.edits().isEmpty()) return new Resolved(null, "no files or edits returned");
        Map<String, String> current = TestInventory.readTestSources(workspace.dir());
        Map<String, String> next = new LinkedHashMap<>(current);
        Map<String, FileChange> changes = new LinkedHashMap<>();
        for (FileEntry f : suite.files()) {
            String problem = f.problem();
            if (problem != null) return new Resolved(null, problem);
            FileChange c = f.toChange();
            String p = c.path();
            if (!p.startsWith("src/test/")) return new Resolved(null, "tests must live under src/test/: " + p);
            if (c.op() == FileChange.Op.DELETE) return new Resolved(null, "deleting tests is not allowed: " + p);
            FileChange.Op op = current.containsKey(p) ? FileChange.Op.MODIFY : FileChange.Op.CREATE;
            changes.put(p, new FileChange(p, op, c.content()));
            next.put(p, c.content());
        }
        for (TestSuite.TestEdit e : suite.edits()) {
            String p = e.path() == null ? "" : e.path().strip().replace('\\', '/');
            if (!p.startsWith("src/test/")) return new Resolved(null, "edits must target src/test/: " + p);
            String base = next.get(p);
            if (base == null) return new Resolved(null, "edit targets a test file that does not exist: " + p);
            if (e.find() == null || e.find().isEmpty()) return new Resolved(null, "edit for " + p + " has an empty 'find'");
            int first = base.indexOf(e.find());
            if (first < 0) return new Resolved(null, "edit 'find' text not found in " + p + ": " + abbreviate(e.find()));
            if (base.indexOf(e.find(), first + 1) >= 0) {
                return new Resolved(null, "edit 'find' text is not unique in " + p + ": " + abbreviate(e.find()));
            }
            String updated = base.substring(0, first) + (e.replace() == null ? "" : e.replace())
                    + base.substring(first + e.find().length());
            next.put(p, updated);
            changes.put(p, new FileChange(p, FileChange.Op.MODIFY, updated));
        }
        Map<String, String> baseline = baselineMethods(ctx.runId());
        if (!baseline.isEmpty()) {
            List<String> problems = BaselineGuard.check(baseline, TestInventory.methods(next), suite.supersededTests());
            if (!problems.isEmpty()) return new Resolved(null, String.join("\n", problems));
        } else if (!suite.supersededTests().isEmpty()) {
            return new Resolved(null, "supersededTests given but this run has no baseline tests");
        }
        return new Resolved(List.copyOf(changes.values()), null);
    }

    private static String abbreviate(String s) {
        String t = s.replace('\n', ' ');
        return t.length() > 80 ? t.substring(0, 80) + "..." : t;
    }

    @Override
    protected String validate(NodeContext ctx, TestSuite suite) {
        return resolve(ctx, suite).problem();
    }

    @Override
    protected Map<String, String> toArtifacts(TestSuite suite) {
        return Map.of();
    }

    @Override
    protected NodeResult finish(NodeContext ctx, TestSuite suite, NodeResult.Usage usage) {
        List<FileChange> changes = resolve(ctx, suite).changes();
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
            Map<String, String> artifacts = new LinkedHashMap<>();
            artifacts.put(StageGraph.TEST_REPORT, Json.write(report));
            if (!suite.supersededTests().isEmpty()) artifacts.put(StageGraph.SUPERSEDED_TESTS, Json.write(suite.supersededTests()));
            return NodeResult.ok(artifacts, changes, usage);
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
    public static boolean mentions(String text, String acId) {
        if (text == null) return false;
        String num = acId.replaceFirst("(?i)^AC[-_]?", "");
        // Surefire reports the method name (ac1_foo), not @DisplayName, so accept both "AC-1" and "ac1_".
        return java.util.regex.Pattern.compile("(?i)(?<![A-Za-z0-9])AC[-_]?" + java.util.regex.Pattern.quote(num) + "(?!\\d)")
                .matcher(text).find();
    }
}
