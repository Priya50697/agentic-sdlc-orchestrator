package com.sdlcagent.orchestrator.persistence;

import java.nio.file.Path;

/** Layout of .orchestrator/runs/<runId>/ — the single place that knows file names. */
public record RunPaths(Path root) {
    public Path runDir(String runId) {
        return root.resolve("runs").resolve(runId);
    }

    public Path stateFile(String runId) {
        return runDir(runId).resolve("state.json");
    }

    public Path auditFile(String runId) {
        return runDir(runId).resolve("audit.jsonl");
    }

    public Path artifactsDir(String runId) {
        return runDir(runId).resolve("artifacts");
    }

    public Path metricsFile(String runId) {
        return runDir(runId).resolve("metrics-summary.json");
    }

    public Path llmCacheDir() {
        return root.resolve("llm-cache");
    }

    public Path workspaceGitDir() {
        return root.resolve("workspace.git");
    }
}
