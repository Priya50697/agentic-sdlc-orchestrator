package com.sdlcagent.orchestrator.engine;

import static com.sdlcagent.orchestrator.engine.StageGraph.IMPACT;
import static com.sdlcagent.orchestrator.engine.StageGraph.PLAN;
import static com.sdlcagent.orchestrator.engine.StageGraph.RELEASE_REPORT;
import static com.sdlcagent.orchestrator.engine.StageGraph.DOC_REPORT;
import static com.sdlcagent.orchestrator.engine.StageGraph.SPEC;
import static com.sdlcagent.orchestrator.engine.StageGraph.TASK_GRAPH;
import static com.sdlcagent.orchestrator.engine.StageGraph.TEST_REPORT;

import com.sdlcagent.orchestrator.governance.ApprovalRequest;
import com.sdlcagent.orchestrator.governance.FileChange;
import com.sdlcagent.orchestrator.governance.GateResult;
import com.sdlcagent.orchestrator.llm.FakeLlmClient;
import com.sdlcagent.orchestrator.llm.LlmRequest;
import com.sdlcagent.orchestrator.llm.LlmResponse;
import com.sdlcagent.orchestrator.observability.AuditEvent;
import com.sdlcagent.orchestrator.observability.AuditEventType;
import com.sdlcagent.orchestrator.observability.AuditLogger;
import com.sdlcagent.orchestrator.observability.MetricsService;
import com.sdlcagent.orchestrator.persistence.RunPaths;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.io.TempDir;

/** Wires a full standard graph where every agent is a FakeLlmClient-backed executor. No real LLM, no workspace. */
abstract class EngineTestBase {
    static final String TASKS = "{\"tasks\":[{\"id\":\"T1\"},{\"id\":\"T2\",\"dependsOn\":[\"T1\"]},"
            + "{\"id\":\"T3\",\"dependsOn\":[\"T1\"]}]}";

    static class RecordingCheckpoints implements CheckpointService {
        final List<String> commits = new CopyOnWriteArrayList<>();
        final List<String> rollbacks = new CopyOnWriteArrayList<>();

        @Override public String checkpoint(String label) {
            String c = "c" + (commits.size() + 1) + ":" + label;
            commits.add(c);
            return c;
        }

        @Override public void rollbackTo(String commit) { rollbacks.add(commit); }
    }

    @TempDir Path dir;
    final Clock clock = Clock.systemUTC();
    final FakeLlmClient llm = new FakeLlmClient(r -> "out:" + r.user().split("#")[0]);
    final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    final Map<String, List<List<String>>> feedbackSeen = new ConcurrentHashMap<>();
    final RecordingCheckpoints checkpoints = new RecordingCheckpoints();

    RunPaths paths() {
        return new RunPaths(dir);
    }

    int callsOf(String nodeId) {
        AtomicInteger c = calls.get(nodeId);
        return c == null ? 0 : c.get();
    }

    NodeExecutor llmExec(String... outputs) {
        return ctx -> {
            calls.computeIfAbsent(ctx.nodeId(), k -> new AtomicInteger()).incrementAndGet();
            feedbackSeen.computeIfAbsent(ctx.nodeId(), k -> new CopyOnWriteArrayList<>()).add(ctx.feedback());
            LlmResponse r = llm.complete(LlmRequest.of("sys", ctx.nodeId() + "#" + ctx.attempt()));
            Map<String, String> out = new LinkedHashMap<>();
            for (String o : outputs) out.put(o, r.text());
            return NodeResult.ok(out, List.of(), new NodeResult.Usage(1, 10, 5, 0.001, false));
        };
    }

    NodeExecutor countOnly() {
        return ctx -> {
            calls.computeIfAbsent(ctx.nodeId(), k -> new AtomicInteger()).incrementAndGet();
            return NodeResult.ok(Map.of());
        };
    }

    /** Implementer that proposes one new Java file per task; wrap to customize the changes. */
    NodeExecutor implExec(java.util.function.BiFunction<NodeContext, Integer, List<FileChange>> changes) {
        NodeExecutor base = llmExec();
        return ctx -> {
            NodeResult r = base.execute(ctx);
            int n = callsOf(ctx.nodeId());
            return NodeResult.ok(Map.of(), changes.apply(ctx, n), r.usage());
        };
    }

    EngineConfig config() {
        EngineConfig c = new EngineConfig();
        c.checkpoints = checkpoints;
        c.executor("requirements", llmExec(SPEC));
        c.executor("codebase-analyst", llmExec(IMPACT));
        NodeExecutor arch = llmExec(PLAN);
        c.executor("architect", ctx -> {
            NodeResult r = arch.execute(ctx);
            Map<String, String> m = new LinkedHashMap<>(r.artifacts());
            m.put(TASK_GRAPH, TASKS);
            return NodeResult.ok(m, List.of(), r.usage());
        });
        c.executor("implementer", implExec((ctx, n) ->
                List.of(FileChange.create("src/main/java/" + ctx.params().get("taskId") + ".java", "class X {}"))));
        c.executor("compile-gate", countOnly());
        c.executor("test-engineer", llmExec(TEST_REPORT));
        c.executor("doc-writer", llmExec(DOC_REPORT));
        c.executor("live-check", ctx -> NodeResult.ok(Map.of(StageGraph.LIVE_REPORT, "{}")));
        c.executor("release-reviewer", llmExec(RELEASE_REPORT));
        for (String g : List.of("requirements-valid", "impact-valid", "architecture-valid", "tests-valid",
                "docs-valid", "release-valid")) {
            c.gate(g, ctx -> GateResult.pass());
        }
        c.expander(StageGraph.TASK_GRAPH_EXPANDER, (owner, outs) -> TaskGraph.parse(outs.get(TASK_GRAPH))
                .toExpansion("architecture", "implementer", "compile-gate", "implementation", List.of(TASK_GRAPH)));
        return c;
    }

    WorkflowEngine newEngine(EngineConfig c) {
        WorkflowEngine e = new WorkflowEngine("r1", paths(), clock, c);
        e.start("test", "build a url shortener", StageGraph.standard(true));
        return e;
    }

    /** Approves every pending request until the run reaches a terminal state. */
    RunSnapshot approveAll(WorkflowEngine e) {
        RunSnapshot s = e.run();
        while (s.state == RunState.WAITING_HUMAN) {
            for (ApprovalRequest p : e.approvals().pending()) {
                e.approvals().resolve(p.id(), ApprovalRequest.Decision.APPROVE, null,
                        ApprovalRequest.Channel.AUTO, "test");
            }
            s = e.run();
        }
        return s;
    }

    /** For custom executors: record the call and the feedback it received. */
    void track(NodeContext ctx) {
        calls.computeIfAbsent(ctx.nodeId(), k -> new AtomicInteger()).incrementAndGet();
        feedbackSeen.computeIfAbsent(ctx.nodeId(), k -> new CopyOnWriteArrayList<>()).add(ctx.feedback());
    }

    /** Runs, approving anything that is not of the wanted kind, until a request of that kind is pending. */
    RunSnapshot runUntilPending(WorkflowEngine e, com.sdlcagent.orchestrator.governance.ApprovalKind wanted) {
        RunSnapshot s = e.run();
        while (s.state == RunState.WAITING_HUMAN && e.approvals().pending().get(0).kind() != wanted) {
            resolve(e, ApprovalRequest.Decision.APPROVE, null);
            s = e.run();
        }
        return s;
    }

    void resolve(WorkflowEngine e, ApprovalRequest.Decision d, String feedback) {
        ApprovalRequest p = e.approvals().pending().get(0);
        e.approvals().resolve(p.id(), d, feedback, ApprovalRequest.Channel.AUTO, "test");
    }

    List<AuditEvent> audit() {
        try {
            return AuditLogger.readAll(paths().auditFile("r1"));
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    long count(AuditEventType t) {
        return audit().stream().filter(e -> e.type() == t).count();
    }

    int indexOf(AuditEventType t, String nodeId) {
        List<AuditEvent> ev = audit();
        for (int i = 0; i < ev.size(); i++) {
            if (ev.get(i).type() == t && nodeId.equals(ev.get(i).nodeId())) return i;
        }
        return -1;
    }

    MetricsService.Summary metrics() {
        return new MetricsService().compute(audit());
    }

    List<String> list(String... xs) {
        return new ArrayList<>(List.of(xs));
    }
}
