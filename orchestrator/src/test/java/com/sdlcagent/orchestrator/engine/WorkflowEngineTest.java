package com.sdlcagent.orchestrator.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.sdlcagent.orchestrator.governance.ApprovalKind;
import com.sdlcagent.orchestrator.governance.ApprovalRequest;
import com.sdlcagent.orchestrator.governance.ApprovalRequest.Decision;
import com.sdlcagent.orchestrator.governance.FileChange;
import com.sdlcagent.orchestrator.governance.GateResult;
import com.sdlcagent.orchestrator.llm.LlmException;
import com.sdlcagent.orchestrator.observability.AuditEventType;
import com.sdlcagent.orchestrator.observability.AuditLogger;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WorkflowEngineTest extends EngineTestBase {

    @Test
    void greenfieldRunsWavesParallelJoinAndBothGates() {
        WorkflowEngine e = newEngine(config());
        RunSnapshot s = approveAll(e);

        assertThat(s.state).isEqualTo(RunState.COMPLETED);
        assertThat(s.nodes.get("codebase-analysis").state).isEqualTo(NodeState.SKIPPED);
        assertThat(s.nodes).containsKeys("impl:T1", "impl:T2", "impl:T3", "wave:0", "wave:1");
        assertThat(s.nodes.values()).allMatch(n -> n.state == NodeState.PASSED || n.state == NodeState.SKIPPED);

        // wave 2 tasks run in parallel: both start before either ends
        assertThat(indexOf(AuditEventType.AGENT_START, "impl:T2")).isLessThan(indexOf(AuditEventType.AGENT_END, "impl:T3"));
        assertThat(indexOf(AuditEventType.AGENT_START, "impl:T3")).isLessThan(indexOf(AuditEventType.AGENT_END, "impl:T2"));
        // testing || docs in parallel, release joins after both
        assertThat(indexOf(AuditEventType.AGENT_START, "docs")).isLessThan(indexOf(AuditEventType.AGENT_END, "testing"));
        assertThat(indexOf(AuditEventType.AGENT_START, "testing")).isLessThan(indexOf(AuditEventType.AGENT_END, "docs"));
        int release = indexOf(AuditEventType.AGENT_START, "release");
        assertThat(release).isGreaterThan(indexOf(AuditEventType.NODE_PASSED, "testing"));
        assertThat(release).isGreaterThan(indexOf(AuditEventType.NODE_PASSED, "docs"));
        // wave gate 1 only after wave 0 gate
        assertThat(indexOf(AuditEventType.AGENT_START, "impl:T2")).isGreaterThan(indexOf(AuditEventType.NODE_PASSED, "wave:0"));

        assertThat(count(AuditEventType.HUMAN_APPROVAL)).isEqualTo(2); // GATE_1 + GATE_2
        assertThat(AuditLogger.verify(paths().auditFile("r1")).valid()).isTrue();
        // requirements, architecture, 3 tasks, testing, docs, release
        assertThat(llm.callCount()).isEqualTo(8);
        var m = metrics();
        assertThat(m.outcome()).isEqualTo("COMPLETED");
        assertThat(m.nodeSuccessRate()).isEqualTo(1.0);
        assertThat(m.llmCalls()).isEqualTo(8);
        assertThat(checkpoints.commits).hasSize(3); // one per implementation task wave member
    }

    @Test
    void brownfieldSafeStopRollsBackToRunStartAndKeepsTheFailedState() {
        EngineConfig c = config();
        c.executor("test-engineer", ctx -> NodeResult.failed(FailureInfo.of(FailureKind.OTHER, "boom")));
        WorkflowEngine e = new WorkflowEngine("r1", paths(), clock, c);
        e.start("brownfield", "extend it", StageGraph.standard(true), "base-commit");
        RunSnapshot s = approveAll(e);

        assertThat(s.state).isEqualTo(RunState.SAFE_STOPPED);
        assertThat(s.lastGoodCheckpoint).isNotEqualTo("base-commit"); // implementation had passed and was checkpointed
        assertThat(checkpoints.rollbacks).containsExactly("base-commit");
        assertThat(checkpoints.tags).singleElement().satisfies(t -> assertThat(t).startsWith("failed-r1="));
        var rollback = audit().stream().filter(x -> x.type() == AuditEventType.ROLLBACK).findFirst().orElseThrow();
        assertThat(rollback.data()).containsEntry("scope", "run-start").containsEntry("to", "base-commit")
                .containsKey("preservedCommit");
    }

    @Test
    void supersededBaselineTestIsAuditedAndShownToTheReviewer() {
        EngineConfig c = config();
        c.executor("test-engineer", ctx -> NodeResult.ok(Map.of(
                StageGraph.TEST_REPORT, "tests",
                StageGraph.SUPERSEDED_TESTS, "[{\"testId\":\"AcceptanceTest#ac6\",\"reason\":\"expiry adds a column\"}]"),
                List.of(FileChange.modify("src/test/java/AcceptanceTest.java", "class T {}")), NodeResult.Usage.NONE));
        WorkflowEngine e = newEngine(c);
        RunSnapshot s = runUntilPending(e, com.sdlcagent.orchestrator.governance.ApprovalKind.RISK_ACTION);

        assertThat(s.state).isEqualTo(RunState.WAITING_HUMAN);
        assertThat(e.approvals().pending().get(0).summary())
                .contains("Existing test changed: AcceptanceTest#ac6").contains("expiry adds a column");
        assertThat(count(AuditEventType.BASELINE_TEST_SUPERSEDED)).isEqualTo(1);
        assertThat(approveAll(e).state).isEqualTo(RunState.COMPLETED);
    }

    @Test
    void retryFeedsErrorsBackAndRecordsMttr() {
        EngineConfig c = config();
        AtomicInteger n = new AtomicInteger();
        NodeExecutor ok = llmExec(StageGraph.TEST_REPORT);
        c.executor("test-engineer", ctx -> {
            track(ctx);
            int i = n.incrementAndGet();
            if (i == 1) throw new LlmException(LlmException.Kind.TIMEOUT, "slow");
            if (i == 2) return NodeResult.failed(FailureInfo.of(FailureKind.COMPILE_TEST, "cannot find symbol Foo"));
            return ok.execute(ctx);
        });
        RunSnapshot s = approveAll(newEngine(c));

        assertThat(s.state).isEqualTo(RunState.COMPLETED);
        assertThat(feedbackSeen.get("testing").get(2)).anyMatch(f -> f.contains("cannot find symbol Foo"));
        var m = metrics();
        assertThat(m.retries()).isEqualTo(2);
        assertThat(m.nodesFailed()).isEqualTo(2);
        assertThat(m.failuresRecovered()).isEqualTo(1);
        assertThat(m.mttrMillis()).isNotNull();
    }

    @Test
    void exhaustedRetriesRollBackAndSafeStop() {
        EngineConfig c = config();
        c.executor("test-engineer", ctx -> NodeResult.failed(FailureInfo.of(FailureKind.OTHER, "boom")));
        RunSnapshot s = approveAll(newEngine(c));

        assertThat(s.state).isEqualTo(RunState.SAFE_STOPPED);
        assertThat(s.nodes.get("testing").state).isEqualTo(NodeState.ROLLED_BACK);
        assertThat(checkpoints.rollbacks).containsExactly(s.lastGoodCheckpoint);
        assertThat(count(AuditEventType.RETRY)).isEqualTo(2);
        assertThat(count(AuditEventType.NODE_FAILED)).isEqualTo(3);
        assertThat(count(AuditEventType.ROLLBACK)).isEqualTo(1);
        assertThat(count(AuditEventType.SAFE_STOP)).isEqualTo(1);
        assertThat(metrics().outcome()).isEqualTo("SAFE_STOPPED");
        assertThat(s.nodes.get("release").state).isEqualTo(NodeState.PENDING);
    }

    @Test
    void mainCodeFailureRoutesBackToOwningTaskAndRerunsPath() {
        EngineConfig c = config();
        AtomicInteger n = new AtomicInteger();
        NodeExecutor ok = llmExec(StageGraph.TEST_REPORT);
        c.executor("test-engineer", ctx -> {
            if (n.incrementAndGet() == 1) {
                track(ctx);
                return NodeResult.failed(new FailureInfo(FailureKind.EXCEPTION_MAIN, "NPE at T2.java:5", "NPE", "impl:T2"));
            }
            return ok.execute(ctx);
        });
        RunSnapshot s = approveAll(newEngine(c));

        assertThat(s.state).isEqualTo(RunState.COMPLETED);
        assertThat(callsOf("impl:T2")).isEqualTo(2);
        assertThat(callsOf("impl:T1")).isEqualTo(1);
        assertThat(callsOf("wave:1")).isEqualTo(2); // compile gate re-ran after the fix
        assertThat(callsOf("testing")).isEqualTo(2);
        assertThat(feedbackSeen.get("impl:T2").get(1)).anyMatch(f -> f.contains("NPE at T2.java:5"));
        assertThat(count(AuditEventType.ROUTE_BACK)).isEqualTo(1);
        assertThat(metrics().routeBacks()).isEqualTo(1);
        assertThat(metrics().mttrMillis()).isNotNull();
    }

    @Test
    void assertionFailureRetriesTestsOnceThenBlamesImplementation() {
        EngineConfig c = config();
        AtomicInteger n = new AtomicInteger();
        NodeExecutor ok = llmExec(StageGraph.TEST_REPORT);
        c.executor("test-engineer", ctx -> n.incrementAndGet() <= 2
                ? NodeResult.failed(new FailureInfo(FailureKind.ASSERTION, "AC-1 expected 302", "AC-1", "impl:T1"))
                : ok.execute(ctx));
        RunSnapshot s = approveAll(newEngine(c));

        assertThat(s.state).isEqualTo(RunState.COMPLETED);
        var m = metrics();
        assertThat(m.retries()).isEqualTo(1);     // 1st failure: test defect
        assertThat(m.routeBacks()).isEqualTo(1);  // 2nd identical failure: implementation defect
        assertThat(callsOf("impl:T1")).isEqualTo(2);
    }

    @Test
    void gateOneRejectionWithFeedbackRerunsArchitect() {
        WorkflowEngine e = newEngine(config());
        RunSnapshot s = e.run();
        assertThat(s.state).isEqualTo(RunState.WAITING_HUMAN);
        assertThat(e.approvals().pending().get(0).kind()).isEqualTo(ApprovalKind.GATE_1);

        resolve(e, Decision.REJECT, "split storage from service");
        s = e.run();
        assertThat(s.state).isEqualTo(RunState.WAITING_HUMAN);
        assertThat(callsOf("architecture")).isEqualTo(2);
        assertThat(feedbackSeen.get("architecture").get(1)).containsExactly("split storage from service");

        s = approveAll(e);
        assertThat(s.state).isEqualTo(RunState.COMPLETED);
        assertThat(count(AuditEventType.INVALIDATED)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void blockingAmbiguityRaisesGateZeroAndAnswersTriggerRerun() {
        EngineConfig c = config();
        c.gate("requirements-valid", ctx -> ctx.attempt() == 1
                ? GateResult.needsApproval(ApprovalKind.GATE_0, "which code length?")
                : GateResult.pass());
        WorkflowEngine e = newEngine(c);
        assertThat(e.run().state).isEqualTo(RunState.WAITING_HUMAN);
        assertThat(e.approvals().pending().get(0).kind()).isEqualTo(ApprovalKind.GATE_0);
        assertThat(e.approvals().pending().get(0).summary()).contains("code length");

        resolve(e, Decision.REJECT, "use 7 characters");
        RunSnapshot s = approveAll(e);
        assertThat(s.state).isEqualTo(RunState.COMPLETED);
        assertThat(feedbackSeen.get("requirements").get(1)).containsExactly("use 7 characters");
    }

    @Test
    void midRunRequirementChangeInvalidatesDownstreamViaHashes() {
        EngineConfig c = config();
        c.executor("requirements", ctx -> {
            calls.computeIfAbsent("requirements", k -> new AtomicInteger()).incrementAndGet();
            return NodeResult.ok(Map.of(StageGraph.SPEC, "spec for: " + ctx.inputs().get(StageGraph.REQUIREMENT)));
        });
        WorkflowEngine e = newEngine(c);
        assertThat(e.run().state).isEqualTo(RunState.WAITING_HUMAN); // architect waiting at Gate 1
        String firstApproval = e.approvals().pending().get(0).id();

        e.updateArtifact(StageGraph.REQUIREMENT, "build a url shortener with expiry");
        RunSnapshot s = approveAll(e);

        assertThat(s.state).isEqualTo(RunState.COMPLETED);
        assertThat(callsOf("requirements")).isEqualTo(2);
        assertThat(callsOf("architecture")).isEqualTo(2);
        assertThat(e.approvals().all().stream().filter(a -> a.id().equals(firstApproval)).findFirst().orElseThrow().status())
                .isEqualTo(ApprovalRequest.Status.CANCELLED);
        assertThat(e.artifacts().get(StageGraph.SPEC).orElseThrow()).contains("expiry");
    }

    @Test
    void highSeverityPolicyViolationRetriesAndCriticalSafeStops() {
        EngineConfig c = config();
        c.executor("implementer", implExec((ctx, n) -> List.of(FileChange.create("src/main/java/A.java",
                n == 1 ? "class A { String password = \"abcdefghijkl1234\"; }" : "class A {}"))));
        RunSnapshot s = approveAll(newEngine(c));
        assertThat(s.state).isEqualTo(RunState.COMPLETED);
        assertThat(count(AuditEventType.POLICY_VIOLATION)).isGreaterThanOrEqualTo(1);
        assertThat(metrics().retries()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void criticalPolicyViolationSafeStopsImmediately() {
        EngineConfig c = config();
        c.executor("implementer", implExec((ctx, n) ->
                List.of(FileChange.create("../outside.txt", "x"))));
        RunSnapshot s = approveAll(newEngine(c));
        assertThat(s.state).isEqualTo(RunState.SAFE_STOPPED);
        assertThat(s.stopReason).contains("critical policy violation");
        assertThat(count(AuditEventType.RETRY)).isZero();
        assertThat(count(AuditEventType.ROLLBACK)).isEqualTo(1);
    }

    @Test
    void highRiskChangeNeedsApprovalAndRejectionForcesRetry() {
        EngineConfig c = config();
        c.executor("implementer", implExec((ctx, n) -> ctx.nodeId().equals("impl:T1") && n == 1
                ? List.of(FileChange.modify("pom.xml", "<project><dependencies><dependency>"
                + "<artifactId>spring-boot-starter-web</artifactId></dependency></dependencies></project>"))
                : List.of(FileChange.create("src/main/java/" + ctx.params().get("taskId") + ".java", "class X {}"))));
        WorkflowEngine e = newEngine(c);
        RunSnapshot s = runUntilPending(e, ApprovalKind.RISK_ACTION);
        assertThat(s.state).isEqualTo(RunState.WAITING_HUMAN);
        ApprovalRequest p = e.approvals().pending().get(0);
        assertThat(p.kind()).isEqualTo(ApprovalKind.RISK_ACTION);
        assertThat(p.nodeId()).isEqualTo("impl:T1");
        assertThat(p.summary()).contains("pom.xml");

        resolve(e, Decision.REJECT, "no new dependencies");
        s = approveAll(e);
        assertThat(s.state).isEqualTo(RunState.COMPLETED);
        assertThat(callsOf("impl:T1")).isEqualTo(2);
        assertThat(feedbackSeen.get("impl:T1").get(1)).anyMatch(f -> f.contains("no new dependencies"));
    }

    @Test
    void specGapEscalatesToHumanAndAnswerIsFedToTheRerun() {
        EngineConfig c = config();
        AtomicInteger n = new AtomicInteger();
        NodeExecutor ok = llmExec(StageGraph.TEST_REPORT);
        c.executor("test-engineer", ctx -> {
            if (n.incrementAndGet() == 1) {
                track(ctx);
                return NodeResult.failed(FailureInfo.of(FailureKind.SPEC_GAP, "AC-3 does not say what happens on expiry"));
            }
            return ok.execute(ctx);
        });
        WorkflowEngine e = newEngine(c);
        assertThat(runUntilPending(e, ApprovalKind.SPEC_GAP).state).isEqualTo(RunState.WAITING_HUMAN);
        ApprovalRequest p = e.approvals().pending().get(0);
        assertThat(p.kind()).isEqualTo(ApprovalKind.SPEC_GAP);
        assertThat(p.nodeId()).isEqualTo("testing");

        resolve(e, Decision.APPROVE, "expired links return 410");
        RunSnapshot s = approveAll(e);
        assertThat(s.state).isEqualTo(RunState.COMPLETED);
        assertThat(feedbackSeen.get("testing").get(1)).containsExactly("expired links return 410");
        assertThat(metrics().retries()).isZero();
    }

    @Test
    void waitingRunResumesFromPersistedSnapshotInNewEngine() {
        EngineConfig c = config();
        WorkflowEngine first = newEngine(c);
        assertThat(first.run().state).isEqualTo(RunState.WAITING_HUMAN);
        int reqCalls = callsOf("requirements");

        WorkflowEngine second = WorkflowEngine.resume("r1", paths(), clock, c);
        assertThat(second.approvals().pending()).hasSize(1);
        RunSnapshot s = approveAll(second);

        assertThat(s.state).isEqualTo(RunState.COMPLETED);
        assertThat(callsOf("requirements")).isEqualTo(reqCalls); // finished work is not redone
        assertThat(count(AuditEventType.RUN_RESUMED)).isEqualTo(1);
        assertThat(AuditLogger.verify(paths().auditFile("r1")).valid()).isTrue();
    }

    @Test
    void abortSafeStopsWithoutRunningAnything() {
        WorkflowEngine e = newEngine(config());
        e.abort("operator pressed stop");
        RunSnapshot s = e.run();
        assertThat(s.state).isEqualTo(RunState.SAFE_STOPPED);
        assertThat(s.stopReason).contains("operator pressed stop");
        assertThat(llm.callCount()).isZero();
    }

    @Test
    void llmCallBudgetStopsTheRun() {
        EngineConfig c = config().budget(3, 8);
        RunSnapshot s = approveAll(newEngine(c));
        assertThat(s.state).isEqualTo(RunState.SAFE_STOPPED);
        assertThat(s.stopReason).contains("LLM call budget");
    }

    @Test
    void backEdgeBudgetPreventsEndlessLoops() {
        EngineConfig c = config().budget(60, 1);
        c.executor("test-engineer", ctx -> NodeResult.failed(
                new FailureInfo(FailureKind.COMPILE_MAIN, "does not compile", "X", "impl:T1")));
        RunSnapshot s = approveAll(newEngine(c));
        assertThat(s.state).isEqualTo(RunState.SAFE_STOPPED);
        assertThat(s.stopReason).contains("back-edge budget");
        assertThat(count(AuditEventType.ROUTE_BACK)).isEqualTo(1);
    }

    @Test
    void unknownGateIsAPreconditionFailureAndSafeStops() {
        EngineConfig c = config();
        c.gates.remove("docs-valid");
        RunSnapshot s = approveAll(newEngine(c));
        assertThat(s.state).isEqualTo(RunState.SAFE_STOPPED);
        assertThat(s.stopReason).contains("unknown gate docs-valid");
    }
}
