package com.sdlcagent.orchestrator.api;

import static com.sdlcagent.orchestrator.engine.StageGraph.DOC_REPORT;
import static com.sdlcagent.orchestrator.engine.StageGraph.IMPACT;
import static com.sdlcagent.orchestrator.engine.StageGraph.PLAN;
import static com.sdlcagent.orchestrator.engine.StageGraph.RELEASE_REPORT;
import static com.sdlcagent.orchestrator.engine.StageGraph.SPEC;
import static com.sdlcagent.orchestrator.engine.StageGraph.TASK_GRAPH;
import static com.sdlcagent.orchestrator.engine.StageGraph.TEST_REPORT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sdlcagent.orchestrator.cli.ConsoleSession;
import com.sdlcagent.orchestrator.engine.EngineConfig;
import com.sdlcagent.orchestrator.engine.NodeResult;
import com.sdlcagent.orchestrator.engine.RunState;
import com.sdlcagent.orchestrator.engine.StageGraph;
import com.sdlcagent.orchestrator.engine.TaskGraph;
import com.sdlcagent.orchestrator.governance.FileChange;
import com.sdlcagent.orchestrator.governance.GateResult;
import com.sdlcagent.orchestrator.service.EngineConfigCustomizer;
import com.sdlcagent.orchestrator.service.RunService;
import com.sdlcagent.orchestrator.workspace.WorkspaceGit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Whole stack (REST, console, RunService, git checkpoints, sandboxed writes) with fake executors and no LLM. */
@SpringBootTest(properties = {
        "orchestrator.llm.state-root=target/test-api/state",
        "orchestrator.workspace.dir=target/test-api/ws"})
@AutoConfigureMockMvc
@Import(RunApiTest.FakeAgents.class)
class RunApiTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TASKS = "{\"tasks\":[{\"id\":\"T1\"},{\"id\":\"T2\",\"dependsOn\":[\"T1\"]}]}";

    @TestConfiguration
    static class FakeAgents {
        @Bean
        @org.springframework.core.annotation.Order(100)
        EngineConfigCustomizer fakeAgents() {
            return (runId, c) -> {
                c.executor("requirements", ctx -> NodeResult.ok(Map.of(SPEC, "spec")));
                c.executor("codebase-analyst", ctx -> NodeResult.ok(Map.of(IMPACT, "impact")));
                c.executor("architect", ctx -> {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put(PLAN, "plan " + ctx.feedback());
                    m.put(TASK_GRAPH, TASKS);
                    return NodeResult.ok(m);
                });
                c.executor("implementer", ctx -> NodeResult.ok(Map.of(), List.of(FileChange.create(
                        "src/main/java/com/sdlcagent/urlshortener/" + ctx.params().get("taskId") + ".java",
                        "package com.sdlcagent.urlshortener; class " + ctx.params().get("taskId") + " {}")),
                        NodeResult.Usage.NONE));
                c.executor("compile-gate", ctx -> NodeResult.ok(Map.of()));
                c.executor("test-engineer", ctx -> NodeResult.ok(Map.of(TEST_REPORT, "tests")));
                c.executor("doc-writer", ctx -> NodeResult.ok(Map.of(DOC_REPORT, "docs")));
                c.executor("release-reviewer", ctx -> NodeResult.ok(Map.of(RELEASE_REPORT, "release")));
                for (String g : List.of("requirements-valid", "impact-valid", "architecture-valid", "tests-valid",
                        "docs-valid", "release-valid")) {
                    c.gate(g, gc -> GateResult.pass());
                }
                c.expander(StageGraph.TASK_GRAPH_EXPANDER, (owner, outs) -> TaskGraph.parse(outs.get(TASK_GRAPH))
                        .toExpansion("architecture", "implementer", "compile-gate", "implementation", List.of(TASK_GRAPH)));
            };
        }
    }

    @Autowired MockMvc mvc;
    @Autowired RunService runs;
    @Autowired WorkspaceGit git;

    @BeforeAll
    static void clean() throws Exception {
        Path root = Path.of("target/test-api");
        if (Files.exists(root)) {
            try (var s = Files.walk(root)) {
                s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private JsonNode getJson(String url) throws Exception {
        return JSON.readTree(mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode await(Supplier<JsonNode> poll, String wantedState) throws Exception {
        long end = System.currentTimeMillis() + 30_000;
        JsonNode s = null;
        while (System.currentTimeMillis() < end) {
            s = poll.get();
            if (wantedState.equals(s.get("state").asText())) return s;
            Thread.sleep(50);
        }
        throw new AssertionError("timed out waiting for " + wantedState + ", last: " + s);
    }

    /** The persisted state can still say WAITING_HUMAN just after an approval, so wait for the request itself. */
    private JsonNode awaitPending(String runId, String kind) throws Exception {
        long end = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < end) {
            JsonNode s = getJson("/api/runs/" + runId);
            JsonNode p = s.get("pendingApprovals");
            if ("WAITING_HUMAN".equals(s.get("state").asText()) && p.size() == 1
                    && kind.equals(p.get(0).get("kind").asText())) return p;
            Thread.sleep(50);
        }
        throw new AssertionError("no pending " + kind + " for " + runId);
    }

    @Test
    void restRunWithBothApprovalsCompletesAndLeavesAuditableTrail() throws Exception {
        String body = JSON.writeValueAsString(Map.of("scenario", "greenfield", "requirement", "build a shortener"));
        String res = mvc.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String runId = JSON.readTree(res).get("runId").asText();

        for (String expectedKind : List.of("GATE_1", "GATE_2")) {
            JsonNode pending = awaitPending(runId, expectedKind);
            assertThat(pending).hasSize(1);
            assertThat(pending.get(0).get("kind").asText()).isEqualTo(expectedKind);
            mvc.perform(post("/api/runs/" + runId + "/approvals/" + pending.get(0).get("id").asText())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"decision\":\"APPROVE\",\"actor\":\"tester\"}"))
                    .andExpect(status().isOk());
        }
        JsonNode done = await(() -> {
            try { return getJson("/api/runs/" + runId); } catch (Exception e) { throw new RuntimeException(e); }
        }, "COMPLETED");
        assertThat(done.get("nodes")).isNotEmpty();

        assertThat(getJson("/api/runs/" + runId + "/audit/verify").get("valid").asBoolean()).isTrue();
        JsonNode metrics = getJson("/api/runs/" + runId + "/metrics");
        assertThat(metrics.get("outcome").asText()).isEqualTo("COMPLETED");
        assertThat(metrics.get("humanApprovals").asInt()).isEqualTo(2);
        assertThat(getJson("/api/runs/" + runId + "/approvals")).hasSize(2);
        assertThat(mvc.perform(get("/api/runs/" + runId + "/artifacts/spec")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).isEqualTo("spec");
        mvc.perform(get("/api/runs/" + runId + "/artifacts/nope")).andExpect(status().isNotFound());
        mvc.perform(get("/api/runs/no-such-run")).andExpect(status().isNotFound());
        assertThat(Path.of("target/test-api/state/runs/" + runId + "/metrics-summary.json")).exists();
        assertThat(getJson("/api/runs")).isNotEmpty();

        // the implementer's files landed in the workspace and were checkpointed
        assertThat(Path.of("target/test-api/ws/src/main/java/com/sdlcagent/urlshortener/T1.java")).exists();
        assertThat(git.log()).anyMatch(l -> l.contains("impl:T1")).anyMatch(l -> l.contains("scaffold"));
    }

    @Test
    void restRejectsBlankRequirement() throws Exception {
        mvc.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON).content("{\"requirement\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void consoleApprovesRejectsWithFeedbackAndDrivesToCompletion() {
        String runId = runs.create("greenfield", "build a shortener", true);
        // GATE_1: reject with feedback (architect reruns), approve; GATE_2: approve
        String script = "r add rate limiting\na\na\n";
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        var snap = new ConsoleSession(runs, new BufferedReader(new StringReader(script)), new PrintStream(buf)).drive(runId);

        assertThat(snap.state).isEqualTo(RunState.COMPLETED);
        assertThat(buf.toString()).contains("[GATE_1]").contains("[GATE_2]").contains("finished: COMPLETED");
        assertThat(runs.artifact(runId, PLAN)).hasValueSatisfying(p -> assertThat(p).contains("add rate limiting"));
        assertThat(runs.approvals(runId)).extracting(a -> a.channel().name()).containsOnly("CONSOLE");
    }

    @Test
    void consoleSafeStopsWhenInputEnds() {
        String runId = runs.create("greenfield", "build a shortener", true);
        var snap = new ConsoleSession(runs, new BufferedReader(new StringReader("")),
                new PrintStream(new ByteArrayOutputStream())).drive(runId);
        assertThat(snap.state).isEqualTo(RunState.SAFE_STOPPED);
    }

    @Test
    void statusIsReadFromDiskWithoutResumingAnEngine() throws Exception {
        String runId = runs.create("greenfield", "build a shortener", true);
        assertThat(runs.run(runId).state).isEqualTo(RunState.WAITING_HUMAN);
        var fresh = new RunService(new com.sdlcagent.orchestrator.persistence.RunPaths(Path.of("target/test-api/state")),
                git, new com.sdlcagent.orchestrator.workspace.SandboxedFileWriter(Path.of("target/test-api/ws")),
                new com.sdlcagent.orchestrator.workspace.Scaffolder(Path.of("target/test-api/ws"), git),
                new org.springframework.beans.factory.support.DefaultListableBeanFactory()
                        .getBeanProvider(EngineConfigCustomizer.class));
        assertThat(fresh.status(runId).state()).isEqualTo("WAITING_HUMAN");
        assertThat(fresh.status(runId).pendingApprovals()).hasSize(1);
    }
}
