package com.sdlcagent.orchestrator.agents.testing;

import static org.assertj.core.api.Assertions.assertThat;

import com.sdlcagent.orchestrator.agents.base.FileEntry;
import com.sdlcagent.orchestrator.agents.base.OwnerResolver;
import com.sdlcagent.orchestrator.agents.base.PromptLoader;
import com.sdlcagent.orchestrator.agents.testing.TestSuite.SupersededTest;
import com.sdlcagent.orchestrator.agents.testing.TestSuite.TestEdit;
import com.sdlcagent.orchestrator.context.CodeIndexer;
import com.sdlcagent.orchestrator.engine.NodeContext;
import com.sdlcagent.orchestrator.llm.FakeLlmClient;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The brownfield rule: baseline tests never vanish, and change only when declared as superseded with a reason. */
class BaselineTestsTest {
    static final String PATH = "src/test/java/com/x/AcceptanceTest.java";
    static final String SRC = """
            package com.x;
            class AcceptanceTest {
                @Test
                void ac6_noAnalyticsStored() {
                    assertThat(fields).containsExactlyInAnyOrder("id", "code", "originalurl");
                    String s = "}{ not a brace";
                }

                @Test
                void ac1_createsLink() {
                    assertThat(post()).isEqualTo(201);
                }

                void helperNotATest() { }
            }
            """;

    @TempDir Path tmp;
    Path ws;
    TestEngineerAgent agent;

    @BeforeEach
    void setUp() throws IOException {
        ws = tmp.resolve("ws");
        write(ws.resolve(PATH), SRC);
        Path runs = tmp.resolve("orch");
        TestInventory.snapshot(ws, runs.resolve("runs").resolve("r1").resolve(TestInventory.BASELINE_DIR));
        agent = new TestEngineerAgent(FakeLlmClient.sequence("{}"), new PromptLoader(), new CodeIndexer(),
                new WorkspaceProperties(ws, Duration.ofSeconds(1), "com.x"), new RunPaths(runs),
                new MavenRunner(ws, Duration.ofSeconds(1)));
    }

    static void write(Path p, String s) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, s);
    }

    String validate(TestSuite suite) {
        return agent.validate(new NodeContext("r1", "testing", "test-engineer", 1, Map.of(), Map.of(), List.of()), suite);
    }

    static TestSuite edits(List<TestEdit> e, List<SupersededTest> s) {
        return new TestSuite("s", List.of(), e, s);
    }

    static final TestEdit FIELD_EDIT = new TestEdit(PATH, "\"id\", \"code\", \"originalurl\"",
            "\"id\", \"code\", \"originalurl\", \"expiresat\"");

    @Test
    void inventoryFindsTestMethodsIgnoresHelpersAndBracesInStrings() {
        Map<String, String> m = TestInventory.methods(ws);
        assertThat(m).containsOnlyKeys("AcceptanceTest#ac6_noAnalyticsStored", "AcceptanceTest#ac1_createsLink");
        assertThat(TestInventory.normalizeId("com.x.AcceptanceTest#ac5_bad(String)[3]")).isEqualTo("AcceptanceTest#ac5_bad");
    }

    @Test
    void declaredEditOfABaselineTestIsAcceptedAndBecomesAModifyChange() {
        var suite = edits(List.of(FIELD_EDIT),
                List.of(new SupersededTest("AcceptanceTest#ac6_noAnalyticsStored", "expiry adds a nullable column")));
        assertThat(validate(suite)).isNull();
    }

    @Test
    void undeclaredChangeToABaselineTestIsRejected() {
        String problem = validate(edits(List.of(FIELD_EDIT), List.of()));
        assertThat(problem).contains("AcceptanceTest#ac6_noAnalyticsStored").contains("not declared in supersededTests");
    }

    @Test
    void declaredButUnchangedTestIsRejected() {
        TestEdit other = new TestEdit(PATH, "isEqualTo(201)", "isEqualTo(201)");
        String problem = validate(edits(List.of(other),
                List.of(new SupersededTest("AcceptanceTest#ac1_createsLink", "r"))));
        assertThat(problem).contains("does not alter it");
    }

    @Test
    void declarationWithoutReasonIsRejected() {
        String problem = validate(edits(List.of(FIELD_EDIT),
                List.of(new SupersededTest("AcceptanceTest#ac6_noAnalyticsStored", " "))));
        assertThat(problem).contains("has no reason");
    }

    @Test
    void rewritingTheFileAndDroppingABaselineTestIsRejected() {
        String dropped = SRC.replace("""
                    @Test
                    void ac1_createsLink() {
                        assertThat(post()).isEqualTo(201);
                    }
                """, "");
        var suite = new TestSuite("s", List.of(new FileEntry(PATH, "CREATE", dropped)));
        assertThat(validate(suite)).contains("removed or renamed").contains("AcceptanceTest#ac1_createsLink");
    }

    @Test
    void editsMustMatchExactlyOnce() {
        assertThat(validate(edits(List.of(new TestEdit(PATH, "nope nope", "x")), List.of())))
                .contains("not found");
        assertThat(validate(edits(List.of(new TestEdit(PATH, "assertThat(", "x")), List.of())))
                .contains("not unique");
        assertThat(validate(edits(List.of(new TestEdit("src/test/java/com/x/Missing.java", "a", "b")), List.of())))
                .contains("does not exist");
        assertThat(validate(edits(List.of(new TestEdit("src/main/java/A.java", "a", "b")), List.of())))
                .contains("src/test/");
    }

    @Test
    void newTestFilesAreFreeAndNothingReturnedIsRejected() {
        var added = new TestSuite("s", List.of(new FileEntry("src/test/java/com/x/NewTest.java", "CREATE",
                "package com.x; class NewTest { @Test void ac2_x() { } }")));
        assertThat(validate(added)).isNull();
        assertThat(validate(new TestSuite("s", List.of()))).contains("no files or edits");
    }

    @Test
    void acceptanceTestNameResolvesToTheTaskListingThatCriterion() {
        String graph = """
                {"tasks":[{"id":"T1","description":"d","dependsOn":[],"targetPaths":["src/main/java/com/x/A.java"],
                           "acceptanceCriteria":["AC-1"]},
                          {"id":"T2","description":"d","dependsOn":["T1"],"targetPaths":["src/main/java/com/x/B.java"],
                           "acceptanceCriteria":["AC-6","AC-7"]}]}""";
        var owner = OwnerResolver.from(graph);
        assertThat(owner.apply("com.x.UrlShortenerAcceptanceTest#ac6_noAnalyticsStored")).isEqualTo("impl:T2");
        assertThat(owner.apply("com.x.UrlShortenerAcceptanceTest#ac16_other")).isNull();
        assertThat(owner.apply("com.x.UrlShortenerAcceptanceTest")).isNull();
    }
}
