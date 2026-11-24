package com.sdlcagent.orchestrator.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runs {@code claude -p} as a subprocess. Flags verified against the installed CLI:
 * tools disabled, no session persistence, no user/project/local settings, no MCP, no skills,
 * run from a neutral temp cwd so this repo's CLAUDE.md is never loaded. The prompt goes via stdin
 * (avoids Windows command-line length limits). The system prompt goes via {@code --system-prompt-file}: as an
 * argument, Windows argv quoting silently truncates it at the first quoted phrase that contains a space.
 */
public class ClaudeCliAdapter implements LlmClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String executable;
    private final String defaultModel;
    private final Duration defaultTimeout;
    private final Path workDir;

    public ClaudeCliAdapter(String executable, String defaultModel, Duration defaultTimeout) {
        this.executable = executable;
        this.defaultModel = defaultModel;
        this.defaultTimeout = defaultTimeout;
        try {
            this.workDir = Files.createTempDirectory("orchestrator-claude-cwd");
        } catch (IOException e) {
            throw new IllegalStateException("cannot create neutral cwd", e);
        }
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        String model = request.model() != null ? request.model() : defaultModel;
        Duration timeout = request.timeout() != null ? request.timeout() : defaultTimeout;
        long start = System.nanoTime();

        Path systemFile = null;
        Process process;
        try {
            if (request.system() != null && !request.system().isBlank()) {
                systemFile = Files.createTempFile(workDir, "system-", ".md");
                Files.writeString(systemFile, request.system(), StandardCharsets.UTF_8);
            }
            process = new ProcessBuilder(buildCommand(model, systemFile))
                    .directory(workDir.toFile())
                    .start();
        } catch (IOException e) {
            deleteQuietly(systemFile);
            throw new LlmException(LlmException.Kind.PROCESS, "cannot start '" + executable + "': " + e.getMessage(), e);
        }
        try {
            return run(process, model, timeout, start, request);
        } finally {
            deleteQuietly(systemFile);
        }
    }

    private static void deleteQuietly(Path file) {
        if (file == null) return;
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // temp file in a temp dir; nothing useful to do
        }
    }

    private LlmResponse run(Process process, String model, Duration timeout, long start, LlmRequest request) {

        // Drain both streams concurrently so a full pipe buffer can never block the child.
        CompletableFuture<String> stdout = CompletableFuture.supplyAsync(() -> read(process.getInputStream()));
        CompletableFuture<String> stderr = CompletableFuture.supplyAsync(() -> read(process.getErrorStream()));
        try {
            process.getOutputStream().write(request.user().getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
        } catch (IOException e) {
            process.destroyForcibly();
            throw new LlmException(LlmException.Kind.PROCESS, "cannot write prompt to claude stdin", e);
        }

        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new LlmException(LlmException.Kind.TIMEOUT, "claude -p exceeded " + timeout);
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new LlmException(LlmException.Kind.PROCESS, "interrupted while waiting for claude", e);
        }

        String out = stdout.join();
        if (process.exitValue() != 0 && out.isBlank()) {
            throw new LlmException(LlmException.Kind.PROCESS,
                    "claude exited " + process.exitValue() + ": " + abbreviate(stderr.join()));
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        return parseEnvelope(out, model, elapsedMs);
    }

    List<String> buildCommand(String model, Path systemFile) {
        List<String> cmd = new ArrayList<>(List.of(
                executable, "-p",
                "--output-format", "json",
                "--tools", "",
                "--no-session-persistence",
                "--setting-sources", "",
                "--strict-mcp-config",
                "--disable-slash-commands"));
        if (model != null && !model.isBlank()) {
            cmd.add("--model");
            cmd.add(model);
        }
        if (systemFile != null) {
            cmd.add("--system-prompt-file");
            cmd.add(systemFile.toAbsolutePath().toString());
        }
        return cmd;
    }

    static LlmResponse parseEnvelope(String json, String requestedModel, long fallbackDurationMs) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (IOException e) {
            throw new LlmException(LlmException.Kind.PROTOCOL, "claude output is not JSON: " + abbreviate(json), e);
        }
        if (root == null || !root.hasNonNull("result") && !root.path("is_error").asBoolean(false)) {
            throw new LlmException(LlmException.Kind.PROTOCOL, "claude envelope has no result: " + abbreviate(json));
        }
        if (root.path("is_error").asBoolean(false)) {
            throw new LlmException(LlmException.Kind.PROCESS, "claude reported an error: " + abbreviate(root.path("result").asText()));
        }
        JsonNode usage = root.path("usage");
        long in = usage.path("input_tokens").asLong(0)
                + usage.path("cache_creation_input_tokens").asLong(0)
                + usage.path("cache_read_input_tokens").asLong(0);
        long out = usage.path("output_tokens").asLong(0);
        String model = requestedModel;
        var models = root.path("modelUsage").fieldNames();
        if (models.hasNext()) model = models.next();
        return new LlmResponse(root.get("result").asText(), model, in, out,
                root.path("total_cost_usd").asDouble(0.0),
                root.path("duration_ms").asLong(fallbackDurationMs), false);
    }

    private static String read(java.io.InputStream in) {
        try {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static String abbreviate(String s) {
        return s.length() <= 500 ? s : s.substring(0, 500) + "...";
    }
}
