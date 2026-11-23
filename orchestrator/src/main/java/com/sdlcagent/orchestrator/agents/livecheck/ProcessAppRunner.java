package com.sdlcagent.orchestrator.agents.livecheck;

import com.sdlcagent.orchestrator.workspace.MavenRunner;
import com.sdlcagent.orchestrator.workspace.WorkspaceProperties;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Packages the workspace with its Maven wrapper and runs the jar on a free port. The process runs from a scratch
 * directory so the app's file-based H2 database never lands in the workspace; the process tree is killed on close.
 */
@Component
public class ProcessAppRunner implements LiveAppRunner {
    private static final Duration BOOT_TIMEOUT = Duration.ofSeconds(90);

    private final MavenRunner maven;
    private final WorkspaceProperties workspace;

    public ProcessAppRunner(MavenRunner maven, WorkspaceProperties workspace) {
        this.maven = maven;
        this.workspace = workspace;
    }

    @Override
    public Running start() throws Exception {
        MavenRunner.Result pkg = maven.mvn("-B", "-q", "package", "-DskipTests");
        if (!pkg.ok()) throw new IllegalStateException("package failed (exit " + pkg.exitCode() + ")");
        Path jar = findJar(workspace.dir().toAbsolutePath().normalize().resolve("target"));
        Path scratch = Files.createTempDirectory("live-check-");
        int port = freePort();
        Process p = new ProcessBuilder(javaBin(), "-jar", jar.toString(), "--server.port=" + port)
                .directory(scratch.toFile()).redirectErrorStream(true)
                .redirectOutput(scratch.resolve("app.log").toFile()).start();
        Running running = new Running() {
            @Override
            public String baseUrl() {
                return "http://localhost:" + port;
            }

            @Override
            public void close() {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
            }
        };
        try {
            awaitUp(p, port);
        } catch (Exception e) {
            running.close();
            throw e;
        }
        return running;
    }

    private static void awaitUp(Process p, int port) throws Exception {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        HttpRequest probe = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/live-check-ping"))
                .timeout(Duration.ofSeconds(3)).GET().build();
        long deadline = System.nanoTime() + BOOT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (!p.isAlive()) throw new IllegalStateException("app exited during boot (code " + p.exitValue() + ")");
            try {
                http.send(probe, HttpResponse.BodyHandlers.discarding()); // any HTTP answer (even 404) = up
                return;
            } catch (IOException notYet) {
                Thread.sleep(500);
            }
        }
        throw new IllegalStateException("app did not answer within " + BOOT_TIMEOUT.toSeconds() + " s");
    }

    private static Path findJar(Path target) throws IOException {
        try (Stream<Path> s = Files.list(target)) {
            List<Path> jars = s.filter(f -> f.toString().endsWith(".jar") && !f.toString().endsWith(".original")).toList();
            if (jars.isEmpty()) throw new IllegalStateException("no jar in " + target);
            return jars.get(0);
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static String javaBin() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }
}
