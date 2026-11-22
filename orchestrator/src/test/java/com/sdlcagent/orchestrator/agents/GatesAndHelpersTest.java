package com.sdlcagent.orchestrator.agents;

import static org.assertj.core.api.Assertions.assertThat;

import com.sdlcagent.orchestrator.agents.architecture.ArchitectureGate;
import com.sdlcagent.orchestrator.agents.architecture.TaskGraphExpander;
import com.sdlcagent.orchestrator.agents.base.AgentRegistrar;
import com.sdlcagent.orchestrator.agents.base.OwnerResolver;
import com.sdlcagent.orchestrator.agents.base.PromptLoader;
import com.sdlcagent.orchestrator.agents.codebase.ImpactGate;
import com.sdlcagent.orchestrator.agents.docs.DocsGate;
import com.sdlcagent.orchestrator.agents.release.ReleaseGate;
import com.sdlcagent.orchestrator.agents.testing.TestsGate;
import com.sdlcagent.orchestrator.context.CodeIndexer;
import com.sdlcagent.orchestrator.engine.EngineConfig;
import com.sdlcagent.orchestrator.engine.FailureKind;
import com.sdlcagent.orchestrator.engine.Node;
import com.sdlcagent.orchestrator.engine.NodeResult;
import com.sdlcagent.orchestrator.engine.StageGraph;
import com.sdlcagent.orchestrator.governance.FileChange;
import com.sdlcagent.orchestrator.governance.GateContext;
import com.sdlcagent.orchestrator.governance.GateResult;
import com.sdlcagent.orchestrator.workspace.WorkspaceProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatesAndHelpersTest {
    static final String PKG = "src/main/java/com/sdlcagent/urlshortener";
    static final String SPEC = """
            {"title":"t","stories":[{"id":"S1","text":"x"}],
             "acceptanceCriteria":[{"id":"AC-1","storyId":"S1","text":"POST creates a short code"},
                                   {"id":"AC-2","storyId":"S1","text":"GET redirects with 302"}]}""";

    private static String graph(String t1Path, String t2Acs) {
        return "{\"tasks\":[{\"id\":\"T1\",\"targetPaths\":[\"" + t1Path + "\"],\"acceptanceCriteria\":[\"AC-1\"]},"
                + "{\"id\":\"T2\",\"dependsOn\":[\"T1\"],\"targetPaths\":[\"" + PKG + "/api/RedirectController.java\"],"
                + "\"acceptanceCriteria\":" + t2Acs + "}]}";
    }

    private GateResult arch(String graphJson) {
        var gate = new ArchitectureGate(new WorkspaceProperties(null, null, null));
        NodeResult r = NodeResult.ok(Map.of(StageGraph.PLAN, "{}", StageGraph.TASK_GRAPH, graphJson));
        return gate.evaluate(new GateContext("r", "architecture", 1, Map.of(StageGraph.SPEC, SPEC), r));
    }

    @Test
    void architectureGateAcceptsCoveredSafeGraph() {
        assertThat(arch(graph(PKG + "/service/LinkService.java", "[\"AC-2\"]")).status()).isEqualTo(GateResult.Status.PASS);
    }

    @Test
    void architectureGateRejectsUncoveredAcUnsafePathsAndLayerViolations() {
        assertThat(arch(graph(PKG + "/service/LinkService.java", "[\"AC-1\"]")).errors().toString())
                .contains("AC-2 is not covered");
        assertThat(arch(graph("../evil/X.java", "[\"AC-2\"]")).errors().toString()).contains("unsafe path");
        assertThat(arch(graph("src/test/java/com/sdlcagent/urlshortener/T.java", "[\"AC-2\"]")).errors().toString())
                .contains("only target src/main");
        assertThat(arch(graph(PKG + "/api/LinkRepository.java", "[\"AC-2\"]")).errors().toString()).contains("layer rule");
        String cyclic = "{\"tasks\":[{\"id\":\"T1\",\"dependsOn\":[\"T2\"]},{\"id\":\"T2\",\"dependsOn\":[\"T1\"]}]}";
        assertThat(arch(cyclic).status()).isEqualTo(GateResult.Status.FAIL);
    }

    @Test
    void expanderBuildsTaskAndWaveNodes() {
        String g = graph(PKG + "/service/LinkService.java", "[\"AC-2\"]");
        var exp = new TaskGraphExpander().expand(Node.stage("architecture", "architect"), Map.of(StageGraph.TASK_GRAPH, g));
        assertThat(exp.nodes()).extracting(n -> n.id).contains("impl:T1", "impl:T2", "wave:0", "wave:1");
        assertThat(exp.extraDependencies()).containsKey("implementation");
    }

    @Test
    void ownerResolverMapsFilesAndTestClassesToTasks() {
        var owner = OwnerResolver.from(graph(PKG + "/service/LinkService.java", "[\"AC-2\"]"));
        assertThat(owner.apply("C:/ws/" + PKG + "/service/LinkService.java")).isEqualTo("impl:T1");
        assertThat(owner.apply("com.sdlcagent.urlshortener.api.RedirectController")).isEqualTo("impl:T2");
        assertThat(owner.apply("com.sdlcagent.urlshortener.api.RedirectControllerTest")).isEqualTo("impl:T2");
        assertThat(owner.apply("Unknown")).isNull();
    }

    @Test
    void docsAndImpactGatesCompareAgainstTheRealCode(@TempDir Path ws) throws Exception {
        Path ctl = ws.resolve(PKG + "/api/RedirectController.java");
        Files.createDirectories(ctl.getParent());
        Files.writeString(ctl, """
                package com.sdlcagent.urlshortener.api;
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class RedirectController {
                    @GetMapping("/{code}") public String go(@PathVariable String code) { return code; }
                    @PostMapping("/api/urls") public String make() { return "x"; }
                }
                """);
        var props = new WorkspaceProperties(ws, null, null);
        var docs = new DocsGate(new CodeIndexer(), props);
        String good = "# S\n## Overview\nx\n## API\n- `GET /{code}`\n- `POST /api/urls`\n## Running\nx\n## Testing\nx\n";
        assertThat(docsResult(docs, good).status()).isEqualTo(GateResult.Status.PASS);
        String missing = good.replace("- `POST /api/urls`\n", "");
        assertThat(docsResult(docs, missing).errors().toString()).contains("missing from the docs");
        String invented = good + "- `DELETE /api/urls/{id}`\n";
        assertThat(docsResult(docs, invented).errors().toString()).contains("do not exist");
        assertThat(docsResult(docs, "# S\n`GET /{code}` `POST /api/urls`").errors().toString()).contains("lacks sections");

        var impact = new ImpactGate(new CodeIndexer(), props);
        NodeResult ok = NodeResult.ok(Map.of(StageGraph.IMPACT,
                "{\"affectedFiles\":[\"" + PKG + "/api/RedirectController.java\"],\"affectedClasses\":[\"RedirectController\"]}"));
        assertThat(impact.evaluate(new GateContext("r", "n", 1, Map.of(), ok)).status()).isEqualTo(GateResult.Status.PASS);
        NodeResult bad = NodeResult.ok(Map.of(StageGraph.IMPACT, "{\"affectedFiles\":[\"src/Ghost.java\"],\"affectedClasses\":[\"Ghost\"]}"));
        assertThat(impact.evaluate(new GateContext("r", "n", 1, Map.of(), bad)).errors().toString()).contains("does not exist");
    }

    private static GateResult docsResult(DocsGate gate, String readme) {
        NodeResult r = NodeResult.ok(Map.of(), List.of(FileChange.create("README.md", readme)), NodeResult.Usage.NONE);
        return gate.evaluate(new GateContext("r", "docs", 1, Map.of(), r));
    }

    @Test
    void testsGateRequiresGreenBuildAndFullAcCoverage() {
        var gate = new TestsGate();
        assertThat(testsResult(gate, "{\"total\":2,\"failures\":0,\"errors\":0,\"uncoveredCriteria\":[]}").status())
                .isEqualTo(GateResult.Status.PASS);
        assertThat(testsResult(gate, "{\"total\":2,\"failures\":1,\"errors\":0}").failure().kind()).isEqualTo(FailureKind.OTHER);
        assertThat(testsResult(gate, "{\"total\":2,\"failures\":0,\"errors\":0,\"uncoveredCriteria\":[\"AC-2\"]}").errors().toString())
                .contains("AC-2");
        assertThat(testsResult(gate, "{\"total\":0}").status()).isEqualTo(GateResult.Status.FAIL);
    }

    private static GateResult testsResult(TestsGate gate, String report) {
        return gate.evaluate(new GateContext("r", "testing", 1, Map.of(),
                NodeResult.ok(Map.of(StageGraph.TEST_REPORT, report))));
    }

    @Test
    void releaseGateChecksFactsNotTheNarrative() {
        var facts = new com.sdlcagent.orchestrator.agents.release.ReleaseFacts(true, 10, 0, 0, List.of(),
                new com.sdlcagent.orchestrator.observability.MetricsService().compute(List.of()));
        assertThat(checkRelease(facts, "READY")).isEmpty();
        assertThat(checkRelease(facts, "NOT_READY")).isNotEmpty();
        var tampered = new com.sdlcagent.orchestrator.agents.release.ReleaseFacts(false, 10, 1, 1, List.of("testing"), facts.metrics());
        assertThat(checkRelease(tampered, "READY")).hasSize(3);
    }

    private static List<String> checkRelease(com.sdlcagent.orchestrator.agents.release.ReleaseFacts f, String verdict) {
        try {
            var m = ReleaseGate.class.getDeclaredMethod("check", f.getClass(), String.class);
            m.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<String> r = (List<String>) m.invoke(null, f, verdict);
            return r;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void registrarWiresAgentsGatesAndExpandersAndRejectsDuplicates() {
        var llm = com.sdlcagent.orchestrator.llm.FakeLlmClient.sequence("{}");
        var agent = new com.sdlcagent.orchestrator.agents.requirements.RequirementsAgent(llm, new PromptLoader());
        var reg = new AgentRegistrar(List.of(agent), List.of(new TestsGate()), List.of(new TaskGraphExpander()));
        EngineConfig cfg = new EngineConfig();
        reg.customize("r", cfg);
        assertThat(cfg.executors).containsKey("requirements");
        assertThat(cfg.gates).containsKey("tests-valid");
        assertThat(cfg.expanders).containsKey(StageGraph.TASK_GRAPH_EXPANDER);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new AgentRegistrar(List.of(agent, agent), List.of(), List.of()))
                .hasMessageContaining("duplicate agent");
    }
}
