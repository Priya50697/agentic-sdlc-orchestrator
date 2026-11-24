package com.sdlcagent.orchestrator.engine;

import com.sdlcagent.orchestrator.governance.ApprovalKind;
import com.sdlcagent.orchestrator.governance.ApprovalRequest;
import com.sdlcagent.orchestrator.governance.FileChange;
import com.sdlcagent.orchestrator.governance.Gate;
import com.sdlcagent.orchestrator.governance.GateContext;
import com.sdlcagent.orchestrator.governance.GateResult;
import com.sdlcagent.orchestrator.governance.HumanApprovalManager;
import com.sdlcagent.orchestrator.governance.PolicyInput;
import com.sdlcagent.orchestrator.governance.Severity;
import com.sdlcagent.orchestrator.governance.Violation;
import com.sdlcagent.orchestrator.llm.LlmException;
import com.sdlcagent.orchestrator.observability.AuditEventType;
import com.sdlcagent.orchestrator.observability.AuditLogger;
import com.sdlcagent.orchestrator.persistence.RunPaths;
import com.sdlcagent.orchestrator.persistence.RunRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;

/**
 * Single-threaded coordinator. Only the thread inside {@link #run()} mutates the snapshot; node attempts
 * (entry gates, agent, exit gates) run on virtual threads and report back through a queue, so there are no
 * races. {@code run()} returns when the run is terminal or idle waiting for a human; call it again after
 * approvals are resolved.
 */
public class WorkflowEngine {
    private static final Object WAKE = new Object();

    private record ExternalArtifact(String id, String content) {}

    private record AbortRequest(String reason) {}

    private record GateRecord(String phase, String gate, String status, List<String> errors) {}

    private record Attempt(String nodeId, int attempt, List<GateRecord> gates, NodeResult result,
                           FailureInfo failure, ApprovalKind gateApproval, String gateApprovalSummary,
                           long durationMs) {}

    private final String runId;
    private final EngineConfig cfg;
    private final AuditLogger audit;
    private final RunRepository repo;
    private final ArtifactStore artifacts;
    private final HumanApprovalManager approvals;
    private final Scheduler scheduler = new Scheduler();
    private final Invalidator invalidator = new Invalidator();
    private final BlockingQueue<Object> events = new LinkedBlockingQueue<>();
    private final Set<String> running = new HashSet<>();
    private final Map<String, String> approvalSummaries = new HashMap<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private RunSnapshot s;

    public WorkflowEngine(String runId, RunPaths paths, Clock clock, EngineConfig cfg) {
        this.runId = runId;
        this.cfg = cfg;
        this.audit = new AuditLogger(paths.auditFile(runId), runId, clock);
        this.repo = new RunRepository(paths);
        this.artifacts = new ArtifactStore(paths, runId);
        this.approvals = new HumanApprovalManager(runId, audit, clock);
        this.approvals.setWakeup(() -> events.offer(WAKE));
    }

    // ---------------------------------------------------------------- lifecycle

    public synchronized RunSnapshot start(String scenario, String requirement, List<Node> graph) {
        return start(scenario, requirement, graph, null);
    }

    /** startCheckpoint: workspace commit to restore if the run safe-stops (brownfield); null keeps last-good rollback. */
    public synchronized RunSnapshot start(String scenario, String requirement, List<Node> graph, String startCheckpoint) {
        s = new RunSnapshot();
        s.startCheckpoint = startCheckpoint;
        s.runId = runId;
        s.scenario = scenario;
        for (Node n : graph) s.nodes.put(n.id, n);
        s.artifactHashes.put(StageGraph.REQUIREMENT, artifacts.put(StageGraph.REQUIREMENT, requirement));
        s.state = RunState.RUNNING;
        List<String> skipped = s.nodes.values().stream().filter(n -> n.state == NodeState.SKIPPED).map(n -> n.id).toList();
        audit.log(AuditEventType.RUN_STARTED, null, AuditLogger.data("scenario", scenario, "skipped", skipped));
        persist();
        return s;
    }

    /** Reload a persisted run (crash recovery or continuing after WAITING_HUMAN in a new process). */
    public static WorkflowEngine resume(String runId, RunPaths paths, Clock clock, EngineConfig cfg) {
        WorkflowEngine e = new WorkflowEngine(runId, paths, clock, cfg);
        e.s = e.repo.load(runId, RunSnapshot.class)
                .orElseThrow(() -> new IllegalArgumentException("no saved run " + runId));
        for (Node n : e.s.nodes.values()) {
            if (n.state == NodeState.RUNNING || n.state == NodeState.VALIDATING || n.state == NodeState.READY) {
                n.state = NodeState.PENDING;
            }
        }
        e.approvals.restore(e.s.approvals);
        if (!e.s.state.isTerminal()) e.s.state = RunState.RUNNING;
        e.audit.log(AuditEventType.RUN_RESUMED, null, AuditLogger.data("state", e.s.state.name()));
        e.persist();
        return e;
    }

    public HumanApprovalManager approvals() {
        return approvals;
    }

    public RunSnapshot snapshot() {
        return s;
    }

    public ArtifactStore artifacts() {
        return artifacts;
    }

    /** Safe-stop request from REST/console; applied by the coordinator on its next iteration. */
    public void abort(String reason) {
        events.offer(new AbortRequest(reason));
        events.offer(WAKE);
    }

    /** External change of an artifact (e.g. the requirement); triggers hash-driven invalidation on next run(). */
    public void updateArtifact(String id, String content) {
        events.offer(new ExternalArtifact(id, content));
    }

    // ---------------------------------------------------------------- coordinator loop

    public synchronized RunSnapshot run() {
        if (s == null) throw new IllegalStateException("start() or resume() first");
        try {
            drainQueue();
            if (s.state.isTerminal()) return s;
            if (s.state == RunState.WAITING_HUMAN || s.state == RunState.CREATED) s.state = RunState.RUNNING;
            while (true) {
                drainQueue();
                applyResolvedApprovals();
                if (s.state.isTerminal()) return s;
                dispatchReady();
                if (s.state.isTerminal()) return s;
                if (!running.isEmpty()) {
                    handle(events.take());
                    continue;
                }
                if (allDone()) {
                    s.state = RunState.COMPLETED;
                    audit.log(AuditEventType.RUN_COMPLETED, null, AuditLogger.data("llmCalls", s.llmCalls));
                    persist();
                    return s;
                }
                if (approvals.hasUnapplied()) continue;
                if (s.nodes.values().stream().anyMatch(n -> n.state == NodeState.WAITING_APPROVAL)) {
                    s.state = RunState.WAITING_HUMAN;
                    persist();
                    if (approvals.hasUnapplied()) { s.state = RunState.RUNNING; continue; }
                    return s;
                }
                if (!scheduler.ready(s).isEmpty()) continue;
                safeStop("deadlock: no runnable node and nothing is waiting", true);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            safeStop("coordinator interrupted", true);
            return s;
        } catch (RuntimeException e) {
            s.state = RunState.ABORTED;
            s.stopReason = e.toString();
            audit.log(AuditEventType.RUN_ABORTED, null, AuditLogger.data("error", e.toString()));
            persist();
            throw e;
        }
    }

    private void drainQueue() {
        Object ev;
        while ((ev = events.poll()) != null) handle(ev);
    }

    private void handle(Object ev) {
        if (ev instanceof Attempt a) onAttempt(a);
        else if (ev instanceof ExternalArtifact x) onExternal(x);
        else if (ev instanceof AbortRequest r) {
            if (!s.state.isTerminal()) safeStop("aborted: " + r.reason(), true);
        }
    }

    private boolean allDone() {
        return s.nodes.values().stream().allMatch(n -> n.state == NodeState.PASSED || n.state == NodeState.SKIPPED);
    }

    // ---------------------------------------------------------------- dispatch

    private void dispatchReady() {
        boolean progressed;
        do {
            progressed = false;
            for (Node n : scheduler.ready(s)) {
                if (s.state.isTerminal()) return;
                if (n.state != NodeState.PENDING && n.state != NodeState.RETRYING && n.state != NodeState.INVALIDATED
                        && n.state != NodeState.READY) continue;
                dispatch(n);
                progressed = true;
            }
        } while (progressed && !s.state.isTerminal());
    }

    private void dispatch(Node n) {
        if (n.type == NodeType.JOIN) {
            completeNode(n);
            return;
        }
        if (s.llmCalls >= cfg.budget.maxLlmCalls()) {
            exhaust(n, "LLM call budget exhausted (" + cfg.budget.maxLlmCalls() + ")");
            return;
        }
        n.state = NodeState.RUNNING;
        n.inputHashes = new LinkedHashMap<>();
        Map<String, String> inputs = new LinkedHashMap<>();
        for (String id : n.consumes) {
            String h = s.artifactHashes.get(id);
            if (h != null) n.inputHashes.put(id, h);
            artifacts.get(id).ifPresent(c -> inputs.put(id, c));
        }
        int attempt = n.attempts + 1;
        audit.log(AuditEventType.AGENT_START, n.id,
                AuditLogger.data("agent", n.agent, "attempt", attempt, "inputHashes", n.inputHashes));
        NodeContext ctx = new NodeContext(runId, n.id, n.agent, attempt, new LinkedHashMap<>(n.params), inputs,
                new ArrayList<>(n.feedback));
        n.feedback = new ArrayList<>();
        List<String> entry = List.copyOf(n.entryGates);
        List<String> exit = List.copyOf(n.exitGates);
        running.add(n.id);
        persist();
        workers.submit(() -> events.add(runAttempt(ctx, entry, exit)));
    }

    /** Worker thread: never throws, never touches the snapshot. */
    private Attempt runAttempt(NodeContext ctx, List<String> entryGates, List<String> exitGates) {
        long t0 = System.nanoTime();
        List<GateRecord> records = new ArrayList<>();
        NodeResult result = null;
        FailureInfo failure = null;
        ApprovalKind gateApproval = null;
        String gateSummary = null;
        try {
            for (String g : entryGates) {
                GateResult r = evalGate(g, new GateContext(ctx.runId(), ctx.nodeId(), ctx.attempt(), ctx.inputs(), null));
                records.add(new GateRecord("ENTRY", g, r.status().name(), r.errors()));
                if (r.status() != GateResult.Status.PASS) {
                    failure = r.failure() != null ? r.failure()
                            : FailureInfo.of(FailureKind.PRECONDITION, "entry gate " + g + " did not pass");
                    break;
                }
            }
            if (failure == null) {
                NodeExecutor ex = cfg.executors.get(ctx.agent());
                if (ex == null) {
                    failure = FailureInfo.of(FailureKind.PRECONDITION, "no executor registered for agent " + ctx.agent());
                } else {
                    try {
                        result = ex.execute(ctx);
                        if (result == null) failure = FailureInfo.of(FailureKind.OTHER, "executor returned null");
                        else if (result.failure() != null) failure = result.failure();
                    } catch (Exception e) {
                        failure = FailureInfo.of(e instanceof LlmException ? FailureKind.TRANSIENT : FailureKind.OTHER,
                                e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                }
            }
            if (failure == null) {
                for (String g : exitGates) {
                    GateResult r = evalGate(g, new GateContext(ctx.runId(), ctx.nodeId(), ctx.attempt(), ctx.inputs(), result));
                    records.add(new GateRecord("EXIT", g, r.status().name(), r.errors()));
                    if (r.status() == GateResult.Status.FAIL) {
                        failure = r.failure() != null ? r.failure() : FailureInfo.of(FailureKind.OTHER, String.join("; ", r.errors()));
                        break;
                    }
                    if (r.status() == GateResult.Status.NEEDS_APPROVAL && gateApproval == null) {
                        gateApproval = r.approvalKind();
                        gateSummary = r.approvalSummary();
                    }
                }
            }
        } catch (Throwable t) {
            failure = FailureInfo.of(FailureKind.OTHER, "worker crashed: " + t);
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        return new Attempt(ctx.nodeId(), ctx.attempt(), records, result, failure, gateApproval, gateSummary, ms);
    }

    private GateResult evalGate(String name, GateContext ctx) {
        Gate g = cfg.gates.get(name);
        if (g == null) return GateResult.fail(FailureInfo.of(FailureKind.PRECONDITION, "unknown gate " + name));
        try {
            return g.evaluate(ctx);
        } catch (Exception e) {
            return GateResult.fail(FailureInfo.of(FailureKind.OTHER, "gate " + name + " crashed: " + e));
        }
    }

    // ---------------------------------------------------------------- applying results

    private void onAttempt(Attempt a) {
        running.remove(a.nodeId());
        Node n = s.nodes.get(a.nodeId());
        if (s.state.isTerminal() || n == null || n.state != NodeState.RUNNING) return;

        NodeResult.Usage u = a.result() == null ? NodeResult.Usage.NONE : a.result().usage();
        if (!u.cacheHit()) s.llmCalls += u.llmCalls();
        audit.log(AuditEventType.AGENT_END, n.id, AuditLogger.data(
                "attempt", a.attempt(), "durationMs", a.durationMs(), "llmCalls", u.llmCalls(),
                "inputTokens", u.inputTokens(),
                "outputTokens", u.outputTokens(), "costUsd", u.costUsd(), "cacheHit", u.cacheHit(),
                "success", a.failure() == null));
        for (GateRecord g : a.gates()) {
            audit.log(g.phase().equals("ENTRY") ? AuditEventType.ENTRY_GATE : AuditEventType.EXIT_GATE, n.id,
                    AuditLogger.data("gate", g.gate(), "status", g.status(), "errors", g.errors()));
        }
        n.state = NodeState.VALIDATING;
        if (a.failure() != null) {
            handleFailure(n, a.failure());
            return;
        }

        NodeResult r = a.result();
        List<Violation> violations = cfg.policyEngine.evaluate(new PolicyInput(n.id, n.agent, r.changes(), r.artifacts()));
        for (Violation v : violations) {
            audit.log(AuditEventType.POLICY_VIOLATION, n.id, AuditLogger.data(
                    "policy", v.policyId(), "severity", v.severity().name(), "message", v.message(), "path", v.path()));
        }
        if (violations.stream().anyMatch(v -> v.severity() == Severity.CRITICAL)) {
            n.state = NodeState.ROLLED_BACK;
            safeStop("critical policy violation in " + n.id + ": "
                    + violations.stream().filter(v -> v.severity() == Severity.CRITICAL)
                    .map(Violation::message).collect(Collectors.joining("; ")), true);
            return;
        }
        List<Violation> blocking = violations.stream().filter(v -> v.severity() == Severity.HIGH).toList();
        if (!blocking.isEmpty()) {
            handleFailure(n, FailureInfo.of(FailureKind.POLICY, "policy violations: "
                    + blocking.stream().map(Violation::message).collect(Collectors.joining("; "))));
            return;
        }

        List<String> outputs = new ArrayList<>();
        r.artifacts().forEach((id, content) -> {
            s.artifactHashes.put(id, artifacts.put(id, content));
            outputs.add(id);
        });
        n.outputArtifactIds = outputs;
        n.pendingChanges = new ArrayList<>(r.changes());
        String superseded = noteSupersededTests(n, r.artifacts().get(StageGraph.SUPERSEDED_TESTS));

        List<ApprovalKind> need = new ArrayList<>();
        if (!cfg.riskClassifier.classify(n.pendingChanges).isEmpty()) need.add(ApprovalKind.RISK_ACTION);
        if (superseded != null && need.contains(ApprovalKind.RISK_ACTION)) {
            approvalSummaries.put(n.id + "|" + ApprovalKind.RISK_ACTION, superseded);
        }
        if (a.gateApproval() != null) {
            need.add(a.gateApproval());
            if (a.gateApprovalSummary() != null) approvalSummaries.put(n.id + "|" + a.gateApproval(), a.gateApprovalSummary());
        }
        if (n.approvalGate != null && !need.contains(n.approvalGate)) need.add(n.approvalGate);
        if (need.isEmpty()) {
            completeNode(n);
        } else {
            n.pendingApprovals = need;
            requestNext(n);
        }
    }

    /** Audits each baseline test the agent changed on purpose and returns a reviewer-facing summary (or null). */
    private String noteSupersededTests(Node n, String json) {
        if (json == null || json.isBlank()) return null;
        try {
            com.fasterxml.jackson.databind.JsonNode arr = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            List<String> lines = new ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode t : arr) {
                String id = t.path("testId").asText(), reason = t.path("reason").asText();
                audit.log(AuditEventType.BASELINE_TEST_SUPERSEDED, n.id, AuditLogger.data("testId", id, "reason", reason));
                lines.add("Existing test changed: " + id + " - " + reason);
            }
            return lines.isEmpty() ? null : String.join("; ", lines);
        } catch (Exception e) {
            return null;
        }
    }

    private void completeNode(Node n) {
        if (n.expander != null) {
            try {
                expand(n);
            } catch (RuntimeException e) {
                handleFailure(n, FailureInfo.of(FailureKind.OTHER, "cannot expand " + n.id + ": " + e.getMessage()));
                return;
            }
        }
        List<FileChange> changes = n.pendingChanges;
        if (!changes.isEmpty()) cfg.changeApplier.apply(runId, n.id, List.copyOf(changes));
        n.pendingChanges = new ArrayList<>();
        n.pendingApprovals = new ArrayList<>();
        int attemptsUsed = n.attempts + 1;
        n.state = NodeState.PASSED;
        n.attempts = 0;
        n.lastError = null;
        if (!changes.isEmpty()) {
            String commit = cfg.checkpoints.checkpoint(n.id);
            if (commit != null) {
                n.checkpointCommit = commit;
                s.lastGoodCheckpoint = commit;
                audit.log(AuditEventType.CHECKPOINT, n.id, AuditLogger.data("commit", commit, "files", changes.size()));
            }
        }
        audit.log(AuditEventType.NODE_PASSED, n.id, AuditLogger.data("attempts", attemptsUsed, "type", n.type.name()));
        sweep();
        persist();
    }

    private void expand(Node owner) {
        NodeExpander ex = cfg.expanders.get(owner.expander);
        if (ex == null) throw new IllegalStateException("no expander registered: " + owner.expander);
        Map<String, String> outs = new LinkedHashMap<>();
        for (String id : owner.outputArtifactIds) artifacts.get(id).ifPresent(c -> outs.put(id, c));
        NodeExpander.Expansion e = ex.expand(owner, outs);
        Set<String> newIds = e.nodes().stream().map(x -> x.id).collect(Collectors.toSet());
        s.nodes.values().removeIf(x -> owner.id.equals(x.createdBy) && !newIds.contains(x.id) && !running.contains(x.id));
        for (Node nn : e.nodes()) {
            Node old = s.nodes.get(nn.id);
            if (old != null && old.specHash != null && Objects.equals(old.specHash, nn.specHash)) continue;
            nn.createdBy = owner.id;
            s.nodes.put(nn.id, nn);
        }
        e.extraDependencies().forEach((id, deps) -> {
            Node t = s.nodes.get(id);
            if (t != null) t.expandedDeps = new ArrayList<>(deps);
        });
    }

    private void sweep() {
        List<Invalidator.Invalidation> inv = invalidator.sweep(s);
        for (Invalidator.Invalidation i : inv) {
            Node x = s.nodes.get(i.nodeId());
            x.pendingApprovals = new ArrayList<>();
            x.pendingChanges = new ArrayList<>();
            approvals.cancel(x.id);
            audit.log(AuditEventType.INVALIDATED, x.id, AuditLogger.data("reason", i.reason()));
        }
        if (!inv.isEmpty() && s.state == RunState.COMPLETED) s.state = RunState.RUNNING;
    }

    private void onExternal(ExternalArtifact x) {
        if (s.state == RunState.SAFE_STOPPED || s.state == RunState.ABORTED) return;
        s.artifactHashes.put(x.id(), artifacts.put(x.id(), x.content()));
        sweep();
        persist();
    }

    // ---------------------------------------------------------------- failure handling

    private void handleFailure(Node n, FailureInfo f) {
        n.pendingChanges = new ArrayList<>();
        n.pendingApprovals = new ArrayList<>();
        n.attempts++;
        n.lastError = f.message();
        audit.log(AuditEventType.NODE_FAILED, n.id, AuditLogger.data(
                "attempt", n.attempts, "kind", f.kind().name(), "message", f.message(), "signature", f.signature()));
        String sig = f.signature() == null ? f.kind().name() : f.signature();
        int seen = s.failureSignatures.merge(n.id + "|" + sig, 1, Integer::sum);
        FailureRouter.Decision d = cfg.router.route(n.id, f, seen);
        if (seen > 1) {
            f = new FailureInfo(f.kind(), f.message() + "\n\nNOTE: this exact error has occurred " + seen
                    + " times; your previous fix did not work. Do not repeat it. Check the EXISTING CODE index for the"
                    + " real constructor/method signatures of the classes you call and use only those.",
                    f.signature(), f.ownerNodeId());
        }

        switch (d.action()) {
            case SAFE_STOP -> exhaust(n, d.reason());
            case ESCALATE_HUMAN -> {
                approvalSummaries.put(n.id + "|" + ApprovalKind.SPEC_GAP, f.message());
                n.pendingApprovals = new ArrayList<>(List.of(ApprovalKind.SPEC_GAP));
                requestNext(n);
            }
            case ROUTE_BACK -> {
                Node target = s.nodes.get(d.targetNodeId());
                if (target == null) {
                    retryOrExhaust(n, f, d);
                } else if (s.backEdges >= cfg.budget.maxBackEdges()) {
                    exhaust(n, "back-edge budget exhausted (" + cfg.budget.maxBackEdges() + ")");
                } else {
                    routeBack(n, target, f, d);
                }
            }
            case RETRY_SAME -> retryOrExhaust(n, f, d);
        }
        persist();
    }

    private void retryOrExhaust(Node n, FailureInfo f, FailureRouter.Decision d) {
        if (n.attempts > n.retryBudget) {
            exhaust(n, "retry budget exhausted for " + n.id + " (" + n.retryBudget + " retries): " + f.message());
            return;
        }
        n.state = NodeState.RETRYING;
        n.feedback = new ArrayList<>(List.of(f.message()));
        audit.log(AuditEventType.RETRY, n.id, AuditLogger.data("attempt", n.attempts + 1, "reason", d.reason()));
    }

    private void routeBack(Node from, Node target, FailureInfo f, FailureRouter.Decision d) {
        s.backEdges++;
        List<String> path = invalidator.pathBetween(s, target.id, from.id);
        audit.log(AuditEventType.ROUTE_BACK, from.id, AuditLogger.data(
                "to", target.id, "kind", f.kind().name(), "reason", d.reason(), "path", path));
        for (String id : path) {
            Node pn = s.nodes.get(id);
            if (pn.state == NodeState.PASSED) {
                pn.state = NodeState.INVALIDATED;
                pn.attempts = 0;
                audit.log(AuditEventType.INVALIDATED, pn.id, AuditLogger.data("reason", "route-back from " + from.id));
            }
        }
        target.state = NodeState.INVALIDATED;
        target.attempts = 0;
        target.feedback = new ArrayList<>(List.of(f.message()));
        audit.log(AuditEventType.INVALIDATED, target.id, AuditLogger.data("reason", "route-back from " + from.id));
        from.state = NodeState.PENDING;
        from.attempts = 0;
    }

    private void exhaust(Node n, String reason) {
        n.state = NodeState.ROLLED_BACK;
        safeStop(reason, true);
    }

    private void safeStop(String reason, boolean rollback) {
        if (rollback) {
            // brownfield: undo the whole run so the baseline is clean again, but keep the failed state under a tag
            String preserved = null;
            if (s.startCheckpoint != null) {
                preserved = cfg.checkpoints.checkpoint("run " + runId + " failed state (kept for inspection)");
                if (preserved != null) cfg.checkpoints.tag("failed-" + runId, preserved);
            }
            String target = s.startCheckpoint != null ? s.startCheckpoint : s.lastGoodCheckpoint;
            if (target != null) cfg.checkpoints.rollbackTo(target);
            audit.log(AuditEventType.ROLLBACK, null, AuditLogger.data("to", target,
                    "scope", s.startCheckpoint != null ? "run-start" : "last-good",
                    "preservedCommit", preserved, "reason", reason));
        }
        s.state = RunState.SAFE_STOPPED;
        s.stopReason = reason;
        audit.log(AuditEventType.SAFE_STOP, null, AuditLogger.data("reason", reason));
        audit.log(AuditEventType.RUN_SAFE_STOPPED, null, AuditLogger.data("reason", reason));
        persist();
    }

    // ---------------------------------------------------------------- approvals

    private void requestNext(Node n) {
        ApprovalKind kind = n.pendingApprovals.remove(0);
        String summary = approvalSummaries.remove(n.id + "|" + kind);
        if (summary == null) {
            summary = kind == ApprovalKind.RISK_ACTION
                    ? cfg.riskClassifier.classify(n.pendingChanges).stream()
                    .map(x -> x.path() + ": " + x.reason()).collect(Collectors.joining("; "))
                    : n.id + " produced " + n.outputArtifactIds;
        }
        approvals.request(n.id, kind, summary);
        n.state = NodeState.WAITING_APPROVAL;
        persist();
    }

    private void applyResolvedApprovals() {
        for (ApprovalRequest r : approvals.drainResolved()) {
            if (s.state.isTerminal()) return;
            applyApproval(r);
        }
    }

    private void applyApproval(ApprovalRequest r) {
        Node n = s.nodes.get(r.nodeId());
        if (n == null || n.state != NodeState.WAITING_APPROVAL) return;
        boolean approved = r.status() == ApprovalRequest.Status.APPROVED;
        String feedback = r.feedback() == null || r.feedback().isBlank() ? null : r.feedback();
        switch (r.kind()) {
            case SPEC_GAP -> {
                if (!approved) {
                    exhaust(n, "requirement gap rejected by human at " + n.id);
                } else {
                    n.feedback = new ArrayList<>(List.of(feedback == null ? "approved without clarification" : feedback));
                    n.attempts = 0;
                    n.state = NodeState.PENDING;
                }
            }
            case RISK_ACTION -> {
                if (approved) advance(n);
                else handleFailure(n, FailureInfo.of(FailureKind.POLICY,
                        "risk action rejected" + (feedback == null ? "" : ": " + feedback)));
            }
            default -> { // GATE_0 / GATE_1 / GATE_2
                if (approved) {
                    advance(n);
                } else if (s.backEdges >= cfg.budget.maxBackEdges()) {
                    exhaust(n, "back-edge budget exhausted after rejection of " + n.id);
                } else {
                    s.backEdges++;
                    n.revisions++;
                    n.attempts = 0;
                    n.pendingChanges = new ArrayList<>();
                    n.pendingApprovals = new ArrayList<>();
                    n.feedback = new ArrayList<>(List.of(feedback == null ? "rejected at " + r.kind() : feedback));
                    n.state = NodeState.PENDING;
                    audit.log(AuditEventType.INVALIDATED, n.id,
                            AuditLogger.data("reason", "rejected at " + r.kind(), "feedback", feedback));
                }
            }
        }
        persist();
    }

    private void advance(Node n) {
        if (n.pendingApprovals.isEmpty()) completeNode(n);
        else requestNext(n);
    }

    private void persist() {
        s.approvals = approvals.all();
        repo.save(runId, s);
    }
}
