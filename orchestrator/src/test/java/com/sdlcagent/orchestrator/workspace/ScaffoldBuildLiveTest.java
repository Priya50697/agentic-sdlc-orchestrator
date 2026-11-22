package com.sdlcagent.orchestrator.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/** Opt-in (needs network/Maven repo, takes minutes the first time): set SCAFFOLD_LIVE=1. */
@EnabledIfEnvironmentVariable(named = "SCAFFOLD_LIVE", matches = "1")
class ScaffoldBuildLiveTest {
    @TempDir Path dir;

    @Test
    void scaffoldedProjectBuildsWithItsOwnWrapper() {
        Path ws = dir.resolve("ws");
        new Scaffolder(ws, new WorkspaceGit(dir.resolve("g.git"), ws)).scaffold();
        MavenRunner runner = new MavenRunner(ws, Duration.ofMinutes(8));
        MavenRunner.Result r = runner.testCompile();
        assertThat(r.ok()).as(r.output()).isTrue();
    }
}
