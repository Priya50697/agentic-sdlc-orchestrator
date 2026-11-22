package com.sdlcagent.orchestrator.workspace;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Runs the workspace's own Maven wrapper with a timeout and captures the (bounded) output. */
public class MavenRunner {
    public record Result(int exitCode, boolean timedOut, String output, long durationMs) {
        public boolean ok() {
            return exitCode == 0 && !timedOut;
        }
    }

    private static final int MAX_OUTPUT_CHARS = 200_000;

    private final Path workspace;
    private final Duration timeout;

    public MavenRunner(Path workspace, Duration timeout) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.timeout = timeout;
    }

    public Result compile() {
        return mvn("-B", "-q", "compile");
    }

    public Result testCompile() {
        return mvn("-B", "-q", "test-compile");
    }

    public Result test() {
        return mvn("-B", "test");
    }

    public Result mvn(String... args) {
        List<String> cmd = new ArrayList<>();
        if (isWindows()) {
            cmd.addAll(List.of("cmd.exe", "/c", workspace.resolve("mvnw.cmd").toString()));
        } else {
            cmd.add(workspace.resolve("mvnw").toString());
        }
        cmd.addAll(List.of(args));
        return exec(cmd, timeout);
    }

    /** Runs any command in the workspace; the whole process tree is killed on timeout. */
    public Result exec(List<String> command, Duration limit) {
        long start = System.nanoTime();
        try {
            Process p = new ProcessBuilder(command).directory(workspace.toFile()).redirectErrorStream(true).start();
            p.getOutputStream().close();
            CompletableFuture<String> out = CompletableFuture.supplyAsync(() -> drain(p.getInputStream()),
                    r -> Thread.ofVirtual().start(r));
            boolean finished = p.waitFor(limit.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
                p.waitFor(5, TimeUnit.SECONDS);
            }
            String text;
            try {
                text = out.get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                text = "(output unavailable: " + e + ")";
            }
            long ms = (System.nanoTime() - start) / 1_000_000;
            return new Result(finished ? p.exitValue() : -1, !finished, text, ms);
        } catch (IOException e) {
            return new Result(-1, false, "cannot start process: " + e.getMessage(), 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, false, "interrupted", 0);
        }
    }

    private static String drain(InputStream in) {
        StringBuilder sb = new StringBuilder();
        try (in) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (sb.length() < MAX_OUTPUT_CHARS) sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
            // process killed
        }
        if (sb.length() > MAX_OUTPUT_CHARS) sb.setLength(MAX_OUTPUT_CHARS);
        return sb.toString();
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
