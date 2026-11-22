package com.sdlcagent.orchestrator.engine;

import com.sdlcagent.orchestrator.persistence.RunPaths;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Artifact contents live as files under runs/<id>/artifacts/; hashes live in the snapshot. */
public class ArtifactStore {
    private final RunPaths paths;
    private final String runId;

    public ArtifactStore(RunPaths paths, String runId) {
        this.paths = paths;
        this.runId = runId;
    }

    private Path file(String id) {
        return paths.artifactsDir(runId).resolve(id.replaceAll("[^A-Za-z0-9._-]", "_"));
    }

    /** Stores the content and returns its hash. */
    public String put(String id, String content) {
        try {
            Path f = file(id);
            Files.createDirectories(f.getParent());
            Files.writeString(f, content, StandardCharsets.UTF_8);
            return Hashing.sha256(content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Optional<String> get(String id) {
        Path f = file(id);
        try {
            return Files.exists(f) ? Optional.of(Files.readString(f, StandardCharsets.UTF_8)) : Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
