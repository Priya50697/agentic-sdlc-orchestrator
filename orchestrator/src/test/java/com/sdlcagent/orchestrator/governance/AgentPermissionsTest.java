package com.sdlcagent.orchestrator.governance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentPermissionsTest {
    private final PolicyEngine engine = new PolicyEngine(List.of(new Policies.AgentScope(AgentPermissions.defaults())));

    private List<Violation> eval(String agent, List<FileChange> changes, Map<String, String> artifacts) {
        return engine.evaluate(new PolicyInput("n", agent, changes, artifacts));
    }

    @Test
    void implementerWritesMainButNotTests() {
        assertThat(eval("implementer", List.of(FileChange.create("src/main/java/a/B.java", "x"),
                FileChange.modify("pom.xml", "x")), Map.of())).isEmpty();
        assertThat(eval("implementer", List.of(FileChange.create("src/test/java/a/BTest.java", "x")), Map.of()))
                .singleElement().satisfies(v -> {
                    assertThat(v.severity()).isEqualTo(Severity.HIGH);
                    assertThat(v.message()).contains("may not write");
                });
    }

    @Test
    void testEngineerOnlyWritesTestsAndCannotDelete() {
        assertThat(eval("test-engineer", List.of(FileChange.create("src/test/java/a/ATest.java", "x")),
                Map.of("test-report", "{}"))).isEmpty();
        assertThat(eval("test-engineer", List.of(FileChange.create("src/main/java/a/A.java", "x")), Map.of())).hasSize(1);
        assertThat(eval("test-engineer", List.of(FileChange.delete("src/test/java/a/ATest.java")), Map.of()).get(0).message())
                .contains("may not delete");
    }

    @Test
    void docWriterIsLimitedToReadmeAndDocs() {
        assertThat(eval("doc-writer", List.of(FileChange.create("README.md", "x"), FileChange.create("docs/api.md", "x"),
                FileChange.create("docs/sub/more.md", "x")), Map.of("doc-report", "{}"))).isEmpty();
        assertThat(eval("doc-writer", List.of(FileChange.create("src/main/java/A.java", "x")), Map.of())).hasSize(1);
        assertThat(eval("doc-writer", List.of(FileChange.create("docs/run.sh", "x")), Map.of())).hasSize(1);
    }

    @Test
    void artifactOwnershipIsEnforcedAndNonWritersCannotWrite() {
        assertThat(eval("architect", List.of(), Map.of("architecture-plan", "{}", "task-graph", "{}"))).isEmpty();
        assertThat(eval("architect", List.of(), Map.of("spec", "{}")).get(0).message()).contains("may not produce artifact 'spec'");
        assertThat(eval("requirements", List.of(FileChange.create("README.md", "x")), Map.of("spec", "{}"))).hasSize(1);
        assertThat(eval("compile-gate", List.of(), Map.of())).isEmpty();
    }

    @Test
    void unknownAgentMayDoNothingAndNullAgentIsSkipped() {
        assertThat(eval("rogue", List.of(FileChange.create("src/main/java/A.java", "x")), Map.of())).hasSize(1);
        assertThat(eval(null, List.of(FileChange.create("anything", "x")), Map.of())).isEmpty();
    }
}
