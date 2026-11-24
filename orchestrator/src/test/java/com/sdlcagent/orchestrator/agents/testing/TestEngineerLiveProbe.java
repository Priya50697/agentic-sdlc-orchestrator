package com.sdlcagent.orchestrator.agents.testing;

import static org.assertj.core.api.Assertions.assertThat;

import com.sdlcagent.orchestrator.agents.base.PromptLoader;
import com.sdlcagent.orchestrator.context.CodeIndexer;
import com.sdlcagent.orchestrator.engine.NodeContext;
import com.sdlcagent.orchestrator.engine.NodeResult;
import com.sdlcagent.orchestrator.engine.StageGraph;
import com.sdlcagent.orchestrator.llm.ClaudeCliAdapter;
import com.sdlcagent.orchestrator.persistence.RunPaths;
import com.sdlcagent.orchestrator.workspace.MavenRunner;
import com.sdlcagent.orchestrator.workspace.TestInventory;
import com.sdlcagent.orchestrator.workspace.WorkspaceProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Opt-in single-stage live probe (TESTENG_PROBE=1, costs roughly $0.3-0.5, needs Maven repo access and a prior
 * brownfield run's {@code .orchestrator/} state). Runs only the test engineer, with the real prompt and the real
 * {@code claude -p}, against the implementation a brownfield run produced, so the baseline-test contract can be
 * checked without paying for a whole run. Set TESTENG_PROBE_RUN / TESTENG_PROBE_TAG to pick another run.
 */
@EnabledIfEnvironmentVariable(named = "TESTENG_PROBE", matches = "1")
class TestEngineerLiveProbe {
    static final String RUN = env("TESTENG_PROBE_RUN", "run-20261124-130050-b1f6");
    static final String TAG = env("TESTENG_PROBE_TAG", "failed-" + RUN);

    @TempDir Path tmp;

    @Test
    void realModelFollowsTheBaselineContractAndTheResultPassesRealMaven() throws Exception {
        Path root = Path.of("..").toAbsolutePath().normalize();
        Path state = root.resolve(".orchestrator");
        Path runDir = state.resolve("runs").resolve(RUN);
        Path ws = tmp.resolve("ws");
        Files.createDirectories(ws);

        // implementation state of the failed run + the baseline tests the run started with
        git(root, "--git-dir=" + state.resolve("workspace.git"), "--work-tree=" + ws, "checkout", TAG, "--", ".");
        copyTree(runDir.resolve(TestInventory.BASELINE_DIR).resolve("src").resolve("test"), ws.resolve("src").resolve("test"));

        var llm = new ClaudeCliAdapter("claude", "sonnet", Duration.ofMinutes(10));
        var agent = new TestEngineerAgent(llm, new PromptLoader(), new CodeIndexer(),
                new WorkspaceProperties(ws, Duration.ofMinutes(5), "com.sdlcagent.urlshortener"),
                new RunPaths(state), new MavenRunner(ws, Duration.ofMinutes(5)));

        Map<String, String> inputs = Map.of(
                StageGraph.SPEC, Files.readString(runDir.resolve("artifacts").resolve(StageGraph.SPEC)),
                StageGraph.PLAN, Files.readString(runDir.resolve("artifacts").resolve(StageGraph.PLAN)));
        NodeResult r = agent.run(new NodeContext(RUN, "testing", "test-engineer", 1, Map.of(), inputs, List.of()));
        report("attempt 1", r);
        if (r.failure() != null) {
            // what the engine does after a first ASSERTION failure: retry the test engineer with the failure text
            r = agent.run(new NodeContext(RUN, "testing", "test-engineer", 2, Map.of(), inputs, List.of(r.failure().message())));
            report("attempt 2", r);
        }


        assertThat(r.failure()).as("test engineer failure").isNull();
        assertThat(r.artifacts()).containsKey(StageGraph.SUPERSEDED_TESTS);
        assertThat(r.changes()).isNotEmpty();
    }

    private static void report(String label, NodeResult r) {
        String f = String.valueOf(r.failure());
        System.out.println("PROBE " + label + " failure    = " + (f.length() > 600 ? f.substring(0, 600) + "..." : f));
        System.out.println("PROBE " + label + " usage      = " + r.usage());
        System.out.println("PROBE " + label + " changes    = " + r.changes().stream().map(c -> c.op() + " " + c.path()).toList());
        System.out.println("PROBE " + label + " superseded = " + r.artifacts().get(StageGraph.SUPERSEDED_TESTS));
    }

    private static void git(Path cwd, String... args) throws IOException, InterruptedException {
        List<String> cmd = new java.util.ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor()).as("git %s: %s", List.of(args), out).isZero();
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                Path dest = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(dest);
                else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(p, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
