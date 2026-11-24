package com.sdlcagent.orchestrator.workspace;

import com.sdlcagent.orchestrator.engine.CheckpointService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Checkpoints the generated workspace with a git dir kept outside it
 * ({@code --git-dir=.orchestrator/workspace.git --work-tree=url-shortener/}), so the outer repository never sees a
 * nested repo and a rollback can only ever touch the workspace.
 */
public class WorkspaceGit implements CheckpointService {
    private final Path gitDir;
    private final Path workTree;

    public WorkspaceGit(Path gitDir, Path workTree) {
        this.gitDir = gitDir.toAbsolutePath().normalize();
        this.workTree = workTree.toAbsolutePath().normalize();
    }

    /** Creates the git dir if needed. Safe to call repeatedly. */
    public synchronized void init() {
        try {
            Files.createDirectories(workTree);
            if (!Files.exists(gitDir.resolve("HEAD"))) {
                Files.createDirectories(gitDir.getParent());
                exec(base("init", "--quiet", "--initial-branch=main"), workTree);
            }
            Path exclude = gitDir.resolve("info").resolve("exclude");
            Files.createDirectories(exclude.getParent());
            Files.writeString(exclude, "target/\n*.log\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized String checkpoint(String label) {
        init();
        git("add", "-A");
        boolean hasHead = hasHead();
        boolean dirty = !git("status", "--porcelain").isBlank();
        if (dirty || !hasHead) {
            git("-c", "user.name=orchestrator", "-c", "user.email=orchestrator@local",
                    "commit", "--quiet", "--allow-empty", "-m", label == null ? "checkpoint" : label);
        }
        return git("rev-parse", "HEAD").trim();
    }

    /** Restores tracked files and removes anything created since the commit. */
    @Override
    public synchronized void rollbackTo(String commit) {
        if (commit == null) return;
        init();
        git("reset", "--hard", "--quiet", commit);
        git("clean", "-fdq");
    }

    @Override
    public synchronized void tag(String name, String commit) {
        init();
        git("tag", "-f", name, commit);
    }

    public synchronized String head() {
        return hasHead() ? git("rev-parse", "HEAD").trim() : null;
    }

    public synchronized List<String> log() {
        if (!hasHead()) return List.of();
        return git("log", "--format=%H %s").lines().toList();
    }

    private boolean hasHead() {
        try {
            exec(base("rev-parse", "--verify", "--quiet", "HEAD"), workTree);
            return true;
        } catch (GitFailure e) {
            return false;
        }
    }

    private List<String> base(String... args) {
        List<String> cmd = new ArrayList<>(List.of("git", "--git-dir=" + gitDir, "--work-tree=" + workTree));
        cmd.addAll(List.of(args));
        return cmd;
    }

    private String git(String... args) {
        return exec(base(args), workTree);
    }

    private static String exec(List<String> cmd, Path cwd) {
        try {
            Process p = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true).start();
            p.getOutputStream().close();
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new GitFailure("git timed out: " + String.join(" ", cmd));
            }
            String text = new String(out, StandardCharsets.UTF_8);
            if (p.exitValue() != 0) throw new GitFailure("git failed (" + p.exitValue() + "): " + String.join(" ", cmd) + "\n" + text);
            return text;
        } catch (IOException e) {
            throw new GitFailure("cannot run git: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitFailure("interrupted");
        }
    }

    public static class GitFailure extends RuntimeException {
        public GitFailure(String message) {
            super(message);
        }
    }
}
