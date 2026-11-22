package com.sdlcagent.orchestrator.service;

import com.sdlcagent.orchestrator.engine.ArtifactStore;
import com.sdlcagent.orchestrator.engine.EngineConfig;
import com.sdlcagent.orchestrator.engine.Node;
import com.sdlcagent.orchestrator.engine.RunSnapshot;
import com.sdlcagent.orchestrator.engine.StageGraph;
import com.sdlcagent.orchestrator.engine.WorkflowEngine;
import com.sdlcagent.orchestrator.governance.ApprovalRequest;
import com.sdlcagent.orchestrator.governance.ApprovalRequest.Channel;
import com.sdlcagent.orchestrator.governance.ApprovalRequest.Decision;
import com.sdlcagent.orchestrator.observability.AuditEvent;
import com.sdlcagent.orchestrator.observability.AuditLogger;
import com.sdlcagent.orchestrator.observability.MetricsService;
import com.sdlcagent.orchestrator.persistence.RunPaths;
import com.sdlcagent.orchestrator.persistence.RunRepository;
import com.sdlcagent.orchestrator.workspace.Scaffolder;
import com.sdlcagent.orchestrator.workspace.SandboxedFileWriter;
import com.sdlcagent.orchestrator.workspace.WorkspaceGit;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Application facade over {@link WorkflowEngine}, shared by REST and the console: creates runs (scaffold + wiring),
 * keeps live engines, runs them (blocking or in the background), resolves approvals, exposes status/metrics.
 */
@Service
public class RunService {
    private static final Logger log = LoggerFactory.getLogger(RunService.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    public record NodeView(String id, String agent, String state, int attempts, String lastError) {}

    public record Status(String runId, String scenario, String state, String stopReason, int llmCalls, int backEdges,
                         List<NodeView> nodes, List<ApprovalRequest> pendingApprovals) {}

    private final RunPaths paths;
    private final Clock clock;
    private final WorkspaceGit git;
    private final SandboxedFileWriter writer;
    private final Scaffolder scaffolder;
    private final List<EngineConfigCustomizer> customizers;
    private final MetricsService metrics = new MetricsService();
    private final RunRepository repo;
    private final Map<String, WorkflowEngine> engines = new ConcurrentHashMap<>();
    private final ExecutorService background = Executors.newVirtualThreadPerTaskExecutor();

    public RunService(RunPaths paths, WorkspaceGit git, SandboxedFileWriter writer, Scaffolder scaffolder,
                      ObjectProvider<EngineConfigCustomizer> customizers) {
        this.paths = paths;
        this.clock = Clock.systemUTC();
        this.git = git;
        this.writer = writer;
        this.scaffolder = scaffolder;
        this.customizers = customizers.orderedStream().toList();
        this.repo = new RunRepository(paths);
    }

    // ------------------------------------------------------------------ lifecycle

    /** Creates and starts (but does not run) a run. Greenfield runs get the scaffold baseline first. */
    public String create(String scenario, String requirement, boolean greenfield) {
        if (requirement == null || requirement.isBlank()) throw new IllegalArgumentException("requirement is empty");
        String runId = "run-" + STAMP.format(LocalDateTime.now(ZoneOffset.UTC)) + "-"
                + UUID.randomUUID().toString().substring(0, 4);
        if (greenfield) scaffolder.scaffold();
        else git.checkpoint("run " + runId + " start");
        WorkflowEngine engine = new WorkflowEngine(runId, paths, clock, newConfig(runId));
        List<Node> graph = StageGraph.standard(greenfield);
        engine.start(scenario == null ? "custom" : scenario, requirement, graph);
        engines.put(runId, engine);
        return runId;
    }

    private EngineConfig newConfig(String runId) {
        EngineConfig cfg = new EngineConfig();
        cfg.checkpoints = git;
        cfg.changeApplier = writer;
        for (EngineConfigCustomizer c : customizers) c.customize(runId, cfg);
        return cfg;
    }

    /** The live engine, or one reloaded from state.json (crash recovery / new process). */
    public synchronized WorkflowEngine engine(String runId) {
        WorkflowEngine e = engines.get(runId);
        if (e != null) return e;
        if (repo.load(runId, RunSnapshot.class).isEmpty()) throw new NoSuchRunException(runId);
        e = WorkflowEngine.resume(runId, paths, clock, newConfig(runId));
        engines.put(runId, e);
        return e;
    }

    /** Blocks until the run is terminal or waiting for a human. Writes metrics-summary.json when terminal. */
    public RunSnapshot run(String runId) {
        WorkflowEngine e = engine(runId);
        RunSnapshot s = e.run();
        if (s.state.isTerminal()) writeMetrics(runId);
        return s;
    }

    public void runAsync(String runId) {
        engine(runId); // fail fast on unknown ids
        background.submit(() -> {
            try {
                run(runId);
            } catch (RuntimeException ex) {
                log.error("run {} failed", runId, ex);
            }
        });
    }

    public void abort(String runId, String reason) {
        engine(runId).abort(reason == null ? "operator" : reason);
        runAsync(runId);
    }

    /** Safe-stops and blocks until the engine has applied it. */
    public RunSnapshot abortAndWait(String runId, String reason) {
        engine(runId).abort(reason);
        return run(runId);
    }

    public void updateRequirement(String runId, String requirement) {
        engine(runId).updateArtifact(StageGraph.REQUIREMENT, requirement);
        runAsync(runId);
    }

    public ApprovalRequest resolve(String runId, String approvalId, Decision decision, String feedback,
                                   Channel channel, String actor) {
        return engine(runId).approvals().resolve(approvalId, decision, feedback, channel, actor);
    }

    // ------------------------------------------------------------------ queries

    public List<Status> list() {
        return repo.listRunIds().stream().map(this::status).toList();
    }

    /** Read-only: loads the last persisted snapshot, never resumes an engine or races the coordinator thread. */
    private RunSnapshot persisted(String runId) {
        return repo.load(runId, RunSnapshot.class).orElseThrow(() -> new NoSuchRunException(runId));
    }

    public Status status(String runId) {
        RunSnapshot s = persisted(runId);
        List<NodeView> nodes = s.nodes.values().stream()
                .map(n -> new NodeView(n.id, n.agent, n.state.name(), n.attempts, n.lastError)).toList();
        WorkflowEngine live = engines.get(runId);
        List<ApprovalRequest> pending = live != null ? live.approvals().pending()
                : s.approvals.stream().filter(ApprovalRequest::isPending).toList();
        return new Status(s.runId, s.scenario, s.state.name(), s.stopReason, s.llmCalls, s.backEdges, nodes, pending);
    }

    public List<ApprovalRequest> approvals(String runId) {
        WorkflowEngine live = engines.get(runId);
        return live != null ? live.approvals().all() : persisted(runId).approvals;
    }

    public MetricsService.Summary metrics(String runId) {
        persisted(runId);
        return metrics.compute(paths.auditFile(runId));
    }

    public List<AuditEvent> audit(String runId) {
        persisted(runId);
        try {
            return AuditLogger.readAll(paths.auditFile(runId));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public AuditLogger.Verification verifyAudit(String runId) {
        persisted(runId);
        return AuditLogger.verify(paths.auditFile(runId));
    }

    public Optional<String> artifact(String runId, String artifactId) {
        persisted(runId);
        return new ArtifactStore(paths, runId).get(artifactId);
    }

    private void writeMetrics(String runId) {
        try {
            metrics.writeSummary(paths.auditFile(runId), paths.metricsFile(runId));
        } catch (RuntimeException ex) {
            log.warn("could not write metrics for {}", runId, ex);
        }
    }

    public static class NoSuchRunException extends RuntimeException {
        public NoSuchRunException(String runId) {
            super("no such run: " + runId);
        }
    }
}
