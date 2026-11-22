package com.sdlcagent.orchestrator.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunRepositoryTest {
    record Snapshot(String runId, String state, Instant updated, Map<String, String> nodes) {}

    @Test
    void saveOverwritesAtomicallyAndLoadsBack(@TempDir Path dir) throws Exception {
        var paths = new RunPaths(dir);
        var repo = new RunRepository(paths);
        assertThat(repo.load("r1", Snapshot.class)).isEmpty();

        repo.save("r1", new Snapshot("r1", "RUNNING", Instant.parse("2026-01-01T00:00:00Z"), Map.of("req", "PASSED")));
        repo.save("r1", new Snapshot("r1", "COMPLETED", Instant.parse("2026-01-01T00:05:00Z"), Map.of("req", "PASSED")));

        Snapshot loaded = repo.load("r1", Snapshot.class).orElseThrow();
        assertThat(loaded.state()).isEqualTo("COMPLETED");
        assertThat(loaded.updated()).isEqualTo(Instant.parse("2026-01-01T00:05:00Z"));
        assertThat(repo.listRunIds()).containsExactly("r1");
        // no leftover temp files
        try (var files = Files.list(paths.runDir("r1"))) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("state.json");
        }
    }
}
