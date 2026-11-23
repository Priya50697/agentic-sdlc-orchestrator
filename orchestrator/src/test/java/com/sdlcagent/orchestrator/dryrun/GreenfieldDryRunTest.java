package com.sdlcagent.orchestrator.dryrun;

import static org.assertj.core.api.Assertions.assertThat;

import com.sdlcagent.orchestrator.agents.livecheck.LiveAppRunner;
import com.sdlcagent.orchestrator.cli.ConsoleSession;
import com.sdlcagent.orchestrator.engine.RunState;
import com.sdlcagent.orchestrator.engine.StageGraph;
import com.sdlcagent.orchestrator.llm.FakeLlmClient;
import com.sdlcagent.orchestrator.llm.LlmClient;
import com.sdlcagent.orchestrator.observability.AuditEvent;
import com.sdlcagent.orchestrator.observability.AuditEventType;
import com.sdlcagent.orchestrator.service.RunService;
import com.sdlcagent.orchestrator.workspace.MavenRunner;
import com.sdlcagent.orchestrator.workspace.WorkspaceProperties;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * P4 dry run: the REAL agents, gates, policies, engine, git checkpoints and sandboxed writes over the whole greenfield
 * graph, with scripted LLM replies and no Maven process. Costs nothing; proves the wiring and the recovery paths
 * (repair of a non-JSON reply, out-of-scope file rejected, Gate 1 reject + replan, compile error routed back).
 */
@SpringBootTest(properties = {
        "orchestrator.llm.state-root=target/test-dryrun/state",
        "orchestrator.workspace.dir=target/test-dryrun/ws",
        "spring.main.allow-bean-definition-overriding=true"})
@Import(GreenfieldDryRunTest.Scripted.class)
class GreenfieldDryRunTest {
    static final GreenfieldFixtures FIXTURES = new GreenfieldFixtures();

    @TestConfiguration
    static class Scripted {
        @Bean(name = "cachingLlmClient")
        @Primary
        FakeLlmClient scriptedLlm() {
            return new FakeLlmClient(FIXTURES::reply);
        }

        @Bean
        @Primary
        LiveAppRunner noLiveApp() {
            return () -> {
                throw new IllegalStateException("no live app in dry run");
            };
        }

        @Bean(name = "mavenRunner")
        MavenRunner fakeMaven(WorkspaceProperties p) {
            return new FakeMaven(p.dir(), new AtomicInteger());
        }
    }

    @Autowired RunService runs;
    @Autowired FakeLlmClient llm;
    @Autowired WorkspaceProperties props;

    @BeforeAll
    static void clean() throws Exception {
        Path root = Path.of("target/test-dryrun");
        if (Files.exists(root)) {
            try (var s = Files.walk(root)) {
                s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    void greenfieldRunsToCompletionThroughAllGatesAndRecoveries() throws Exception {
        String runId = runs.create("greenfield", "Build a URL shortener.", true);
        // Gate 1: reject with feedback (architect replans), approve; Gate 2: approve
        String script = "r also expose click stats\na\na\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        var snap = new ConsoleSession(runs, new BufferedReader(new StringReader(script)), new PrintStream(out)).drive(runId);

        assertThat(snap.state).as(out.toString() + "\n" + snap.stopReason).isEqualTo(RunState.COMPLETED);
        assertThat(out.toString()).contains("[GATE_1]").contains("[GATE_2]");

        // every stage passed; codebase analysis skipped for greenfield
        List<AuditEvent> audit = runs.audit(runId);
        assertThat(audit).filteredOn(e -> e.type() == AuditEventType.NODE_PASSED).extracting(AuditEvent::nodeId)
                .contains("requirements", "architecture", "impl:T1", "impl:T2", "impl:T3", "impl:T4",
                        "wave:0", "wave:1", "wave:2", "implementation", "testing", "docs", "live-check", "release");
        assertThat(runs.status(runId).nodes()).filteredOn(n -> n.id().equals("codebase-analysis"))
                .extracting(RunService.NodeView::state).containsExactly("SKIPPED");

        // recovery paths
        var metrics = runs.metrics(runId);
        assertThat(metrics.routeBacks()).as("wave 1 compile error routes back to impl:T1").isGreaterThanOrEqualTo(1);
        assertThat(metrics.failuresRecovered()).isGreaterThanOrEqualTo(1);
        assertThat(FIXTURES.architectCalls.get()).as("repair + Gate 1 replan").isEqualTo(3);
        assertThat(llm.requests()).filteredOn(r -> r.system().contains("Software Architect")).last()
                .satisfies(r -> assertThat(r.user()).contains("also expose click stats"));
        assertThat(FIXTURES.t3Calls.get()).as("T3 first reply wrote a file it does not own").isGreaterThanOrEqualTo(2);

        // artifacts and audit integrity
        for (String id : List.of(StageGraph.SPEC, StageGraph.PLAN, StageGraph.TASK_GRAPH, StageGraph.TEST_REPORT,
                StageGraph.DOC_REPORT, StageGraph.RELEASE_REPORT, "release-report.md")) {
            assertThat(runs.artifact(runId, id)).as(id).isPresent();
        }
        assertThat(runs.verifyAudit(runId).valid()).isTrue();
        assertThat(metrics.nodeSuccessRate()).isGreaterThan(0.5);

        // workspace: generated sources, tests and docs landed through the sandbox
        Path ws = props.dir();
        for (String f : List.of("dao/ShortUrl.java", "dao/ShortUrlRepository.java", "provider/UrlSafetyProvider.java",
                "provider/StubUrlSafetyProvider.java", "service/LinkService.java", "controller/LinkController.java")) {
            assertThat(ws.resolve(GreenfieldFixtures.PKG + f)).as(f).exists();
        }
        assertThat(ws.resolve(GreenfieldFixtures.PKG + "service/LinkHelper.java")).doesNotExist();
        assertThat(ws.resolve("src/test/java/com/sdlcagent/urlshortener/LinkApiTest.java")).exists();
        assertThat(ws.resolve("README.md")).exists();
        assertThat(runs.artifact(runId, StageGraph.TEST_REPORT).orElseThrow()).contains("\"uncoveredCriteria\" : [ ]");
    }
}
