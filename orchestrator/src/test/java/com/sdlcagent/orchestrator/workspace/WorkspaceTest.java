package com.sdlcagent.orchestrator.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sdlcagent.orchestrator.governance.FileChange;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceTest {
    @TempDir Path dir;

    private Path ws() {
        return dir.resolve("ws");
    }

    private WorkspaceGit git() {
        return new WorkspaceGit(dir.resolve(".orch").resolve("workspace.git"), ws());
    }

    // ---------------------------------------------------------------- sandbox

    @Test
    void writerCreatesModifiesDeletesInsideWorkspace() throws IOException {
        Files.createDirectories(ws());
        SandboxedFileWriter w = new SandboxedFileWriter(ws());
        w.apply("r", "n", List.of(FileChange.create("src/main/java/A.java", "class A {}")));
        assertThat(w.read("src/main/java/A.java")).isEqualTo("class A {}");
        w.apply("r", "n", List.of(FileChange.modify("src/main/java/A.java", "class A2 {}")));
        assertThat(w.read("src/main/java/A.java")).isEqualTo("class A2 {}");
        w.apply("r", "n", List.of(FileChange.delete("src/main/java/A.java")));
        assertThat(ws().resolve("src/main/java/A.java")).doesNotExist();
    }

    @Test
    void writerRejectsEscapesAndWritesNothingFromABadBatch() throws IOException {
        Files.createDirectories(ws());
        SandboxedFileWriter w = new SandboxedFileWriter(ws());
        for (String bad : List.of("../evil.txt", "a/../../evil.txt", "/etc/evil", "C:\\evil.txt", "", "a\\..\\..\\x")) {
            assertThatThrownBy(() -> w.resolve(bad)).isInstanceOf(SandboxedFileWriter.SandboxViolation.class);
        }
        assertThatThrownBy(() -> w.apply("r", "n", List.of(
                FileChange.create("good.txt", "x"), FileChange.create("../evil.txt", "y"))))
                .isInstanceOf(SandboxedFileWriter.SandboxViolation.class);
        assertThat(ws().resolve("good.txt")).doesNotExist();
        assertThat(dir.resolve("evil.txt")).doesNotExist();
    }

    // ---------------------------------------------------------------- git checkpoints

    @Test
    void checkpointAndRollbackRestoreTrackedAndRemoveNewFiles() throws IOException {
        WorkspaceGit git = git();
        Files.createDirectories(ws());
        Files.writeString(ws().resolve("a.txt"), "1");
        String c1 = git.checkpoint("one");

        Files.writeString(ws().resolve("a.txt"), "2");
        Files.writeString(ws().resolve("b.txt"), "new");
        git.rollbackTo(c1);

        assertThat(Files.readString(ws().resolve("a.txt"))).isEqualTo("1");
        assertThat(ws().resolve("b.txt")).doesNotExist();
        assertThat(ws().resolve(".git")).as("no nested repo in the workspace").doesNotExist();
    }

    @Test
    void checkpointWithoutChangesReturnsSameCommitAndTargetIsIgnored() throws IOException {
        WorkspaceGit git = git();
        Files.createDirectories(ws().resolve("target"));
        Files.writeString(ws().resolve("a.txt"), "1");
        Files.writeString(ws().resolve("target/x.class"), "bytes");
        String c1 = git.checkpoint("one");
        assertThat(git.checkpoint("again")).isEqualTo(c1);
        assertThat(git.log()).hasSize(1);

        Files.writeString(ws().resolve("a.txt"), "2");
        String c2 = git.checkpoint("two");
        assertThat(c2).isNotEqualTo(c1);
        git.rollbackTo(c1);
        assertThat(ws().resolve("target/x.class")).as("ignored build output is left alone").exists();
    }

    // ---------------------------------------------------------------- scaffold

    @Test
    void scaffoldWritesBaselineOnceAndCheckpointsIt() {
        WorkspaceGit git = git();
        Scaffolder s = new Scaffolder(ws(), git);
        String c1 = s.scaffold();
        assertThat(c1).isNotBlank();
        for (String f : List.of("pom.xml", "mvnw", "mvnw.cmd", ".mvn/wrapper/maven-wrapper.properties",
                "src/main/java/com/sdlcagent/urlshortener/Application.java", "src/main/resources/application.yml")) {
            assertThat(ws().resolve(f)).as(f).exists();
        }
        assertThat(s.scaffold()).isEqualTo(c1);
        assertThat(git.log()).hasSize(1);
    }

    // ---------------------------------------------------------------- process runner

    @Test
    void runnerCapturesOutputAndExitCode() throws IOException {
        Files.createDirectories(ws());
        MavenRunner r = new MavenRunner(ws(), Duration.ofSeconds(30));
        MavenRunner.Result res = r.exec(echo("hello-runner"), Duration.ofSeconds(30));
        assertThat(res.ok()).isTrue();
        assertThat(res.output()).contains("hello-runner");
    }

    @Test
    void runnerKillsOnTimeout() throws IOException {
        Files.createDirectories(ws());
        MavenRunner r = new MavenRunner(ws(), Duration.ofSeconds(30));
        long t0 = System.nanoTime();
        MavenRunner.Result res = r.exec(sleep(30), Duration.ofMillis(500));
        assertThat(res.timedOut()).isTrue();
        assertThat(res.ok()).isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(15));
    }

    private static List<String> echo(String s) {
        return MavenRunner.isWindows() ? List.of("cmd.exe", "/c", "echo " + s) : List.of("echo", s);
    }

    private static List<String> sleep(int seconds) {
        return MavenRunner.isWindows()
                ? List.of("ping", "-n", String.valueOf(seconds + 1), "127.0.0.1")
                : List.of("sleep", String.valueOf(seconds));
    }
}
