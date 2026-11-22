package com.sdlcagent.orchestrator.workspace;

import com.sdlcagent.orchestrator.engine.ChangeApplier;
import com.sdlcagent.orchestrator.governance.FileChange;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Writes vetted changes into the workspace and nowhere else. Last line of defence behind the sandbox policy. */
public class SandboxedFileWriter implements ChangeApplier {
    private final Path root;

    public SandboxedFileWriter(Path workspaceRoot) {
        this.root = workspaceRoot.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    /** Resolves a workspace-relative path; throws if it is absolute, contains "..", or escapes the root. */
    public Path resolve(String relative) {
        if (relative == null || relative.isBlank()) throw new SandboxViolation("empty path");
        String p = relative.replace('\\', '/');
        if (p.startsWith("/") || p.matches("^[A-Za-z]:.*")) throw new SandboxViolation("absolute path: " + relative);
        for (String seg : p.split("/")) {
            if (seg.equals("..")) throw new SandboxViolation("path traversal: " + relative);
        }
        Path target = root.resolve(p).normalize();
        if (!target.startsWith(root)) throw new SandboxViolation("escapes workspace: " + relative);
        // a symlink inside the workspace must not lead out of it
        Path real = nearestExisting(target);
        try {
            if (!real.toRealPath().startsWith(root.toRealPath())) {
                throw new SandboxViolation("escapes workspace via link: " + relative);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return target;
    }

    private Path nearestExisting(Path p) {
        Path cur = p;
        while (cur != null && !Files.exists(cur)) cur = cur.getParent();
        return cur == null ? root : cur;
    }

    @Override
    public void apply(String runId, String nodeId, List<FileChange> changes) {
        // validate every path first so a bad change cannot leave a half-applied set
        List<Path> targets = changes.stream().map(c -> resolve(c.path())).toList();
        try {
            for (int i = 0; i < changes.size(); i++) {
                FileChange c = changes.get(i);
                Path t = targets.get(i);
                switch (c.op()) {
                    case CREATE, MODIFY -> {
                        Files.createDirectories(t.getParent());
                        Files.writeString(t, c.content() == null ? "" : c.content(), StandardCharsets.UTF_8);
                    }
                    case DELETE -> Files.deleteIfExists(t);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String read(String relative) {
        try {
            return Files.readString(resolve(relative), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static class SandboxViolation extends RuntimeException {
        public SandboxViolation(String message) {
            super(message);
        }
    }
}
