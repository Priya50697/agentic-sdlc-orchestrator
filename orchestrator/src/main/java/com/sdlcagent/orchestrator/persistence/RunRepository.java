package com.sdlcagent.orchestrator.persistence;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Persists one JSON snapshot per run (state.json), overwritten atomically on every state transition,
 * so a crashed run can be resumed from the last committed snapshot. The snapshot type is owned by the
 * engine (P2); this class only needs it to be Jackson-serializable.
 */
public class RunRepository {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** Process-wide: the engine and read-only API views use separate instances over the same files, and on Windows a
     *  reader holding state.json open makes the replacing move fail. */
    private static final Object FILE_LOCK = new Object();

    private final RunPaths paths;

    public RunRepository(RunPaths paths) {
        this.paths = paths;
    }

    public void save(String runId, Object snapshot) {
        Path target = paths.stateFile(runId);
        synchronized (FILE_LOCK) {
            try {
                Files.createDirectories(target.getParent());
                Path tmp = Files.createTempFile(target.getParent(), "state", ".tmp");
                MAPPER.writeValue(tmp.toFile(), snapshot);
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("cannot persist run " + runId, e);
            }
        }
    }

    public <T> Optional<T> load(String runId, Class<T> type) {
        Path file = paths.stateFile(runId);
        synchronized (FILE_LOCK) {
            if (!Files.exists(file)) return Optional.empty();
            try {
                return Optional.of(MAPPER.readValue(file.toFile(), type));
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read run " + runId, e);
            }
        }
    }

    public List<String> listRunIds() {
        Path runs = paths.root().resolve("runs");
        if (!Files.isDirectory(runs)) return List.of();
        try (Stream<Path> s = Files.list(runs)) {
            return s.filter(p -> Files.exists(p.resolve("state.json")))
                    .map(p -> p.getFileName().toString()).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
