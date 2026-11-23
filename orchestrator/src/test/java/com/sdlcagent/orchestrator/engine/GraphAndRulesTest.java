package com.sdlcagent.orchestrator.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sdlcagent.orchestrator.engine.FailureRouter.Action;
import com.sdlcagent.orchestrator.governance.ApprovalKind;
import com.sdlcagent.orchestrator.governance.ApprovalRequest.Channel;
import com.sdlcagent.orchestrator.governance.ApprovalRequest.Decision;
import com.sdlcagent.orchestrator.governance.FileChange;
import com.sdlcagent.orchestrator.governance.HumanApprovalManager;
import com.sdlcagent.orchestrator.governance.PolicyEngine;
import com.sdlcagent.orchestrator.governance.PolicyInput;
import com.sdlcagent.orchestrator.governance.RiskClassifier;
import com.sdlcagent.orchestrator.governance.Severity;
import com.sdlcagent.orchestrator.governance.Violation;
import com.sdlcagent.orchestrator.observability.AuditEventType;
import com.sdlcagent.orchestrator.observability.AuditLogger;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GraphAndRulesTest {

    @Test
    void taskGraphComputesWavesAndDetectsProblems() {
        TaskGraph g = TaskGraph.parse(EngineTestBase.TASKS);
        assertThat(g.validate()).isEmpty();
        assertThat(g.waves()).hasSize(2);
        assertThat(g.waves().get(0)).extracting(TaskGraph.TaskSpec::id).containsExactly("T1");
        assertThat(g.waves().get(1)).extracting(TaskGraph.TaskSpec::id).containsExactly("T2", "T3");

        var cyclic = new TaskGraph(List.of(
                new TaskGraph.TaskSpec("A", null, List.of("B"), null, null),
                new TaskGraph.TaskSpec("B", null, List.of("A"), null, null)));
        assertThat(cyclic.validate()).contains("task graph contains a cycle");
        var unknown = new TaskGraph(List.of(new TaskGraph.TaskSpec("A", null, List.of("Z"), null, null)));
        assertThat(unknown.validate()).anyMatch(e -> e.contains("unknown task Z"));
        assertThat(new TaskGraph(List.of()).validate()).contains("task graph is empty");
        assertThatThrownBy(() -> TaskGraph.parse("{}")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void expansionBuildsTaskAndWaveGateNodes() {
        var x = TaskGraph.parse(EngineTestBase.TASKS).toExpansion("architecture", "implementer", "compile-gate",
                "implementation", List.of("task-graph"));
        assertThat(x.nodes()).extracting(n -> n.id).containsExactly("impl:T1", "wave:0", "impl:T2", "impl:T3", "wave:1");
        Node t2 = x.nodes().get(2);
        assertThat(t2.dependsOn).containsExactly("architecture", "impl:T1", "wave:0");
        assertThat(x.extraDependencies()).containsEntry("implementation", List.of("wave:1"));
    }

    @Test
    void failureRouterRules() {
        FailureRouter r = new FailureRouter();
        assertThat(r.route("testing", FailureInfo.of(FailureKind.COMPILE_TEST, "x"), false).action()).isEqualTo(Action.RETRY_SAME);
        var main = new FailureInfo(FailureKind.COMPILE_MAIN, "x", null, "impl:T1");
        assertThat(r.route("testing", main, false).targetNodeId()).isEqualTo("impl:T1");
        assertThat(r.route("impl:T1", main, false).action()).isEqualTo(Action.RETRY_SAME); // no separate owner
        var assertion = new FailureInfo(FailureKind.ASSERTION, "x", "AC-1", "impl:T1");
        assertThat(r.route("testing", assertion, false).action()).isEqualTo(Action.RETRY_SAME);
        assertThat(r.route("testing", assertion, true).action()).isEqualTo(Action.ROUTE_BACK);
        assertThat(r.route("testing", FailureInfo.of(FailureKind.SPEC_GAP, "x"), false).action()).isEqualTo(Action.ESCALATE_HUMAN);
        assertThat(r.route("testing", FailureInfo.of(FailureKind.PRECONDITION, "x"), false).action()).isEqualTo(Action.SAFE_STOP);
    }

    @Test
    void repeatedCodeDefectStopsEarlyInsteadOfBurningTheBudget() {
        FailureRouter r = new FailureRouter();
        var main = new FailureInfo(FailureKind.COMPILE_MAIN, "x", "Svc.java:36", "impl:T5");
        assertThat(r.route("wave:1", main, 1).action()).isEqualTo(Action.ROUTE_BACK);
        assertThat(r.route("wave:1", main, 2).action()).isEqualTo(Action.ROUTE_BACK);
        var stop = r.route("wave:1", main, FailureRouter.MAX_SAME_CODE_FAILURE);
        assertThat(stop.action()).isEqualTo(Action.SAFE_STOP);
        assertThat(stop.reason()).contains("Svc.java:36").contains("not converging");
        // assertions keep their own rule (retry once, then route back)
        var assertion = new FailureInfo(FailureKind.ASSERTION, "x", "AC-1", "impl:T1");
        assertThat(r.route("testing", assertion, 3).action()).isEqualTo(Action.ROUTE_BACK);
    }

    @Test
    void invalidatorFindsChangedInputsAndCascadesThroughJoins() {
        RunSnapshot s = new RunSnapshot();
        Node a = Node.stage("a", "x").consumes("art");
        a.state = NodeState.PASSED;
        a.inputHashes.put("art", "old");
        Node j = Node.join("j").dependsOn("a");
        j.state = NodeState.PASSED;
        s.nodes.put("a", a);
        s.nodes.put("j", j);
        s.artifactHashes.put("art", "new");
        var out = new Invalidator().sweep(s);
        assertThat(out).extracting(Invalidator.Invalidation::nodeId).containsExactly("a", "j");
        assertThat(a.state).isEqualTo(NodeState.INVALIDATED);
        assertThat(j.state).isEqualTo(NodeState.INVALIDATED);
    }

    @Test
    void policiesClassifyViolationsBySeverity() {
        PolicyEngine pe = PolicyEngine.defaults();
        List<Violation> v = pe.evaluate(new PolicyInput("n", List.of(
                FileChange.create("src/main/java/A.java", "class A { void f(){ Runtime.getRuntime().exec(\"ls\"); System.exit(1);} }"),
                FileChange.create("key.txt", "AKIAABCDEFGHIJKLMNOP"),
                FileChange.create("../evil", "x"),
                FileChange.create(".orchestrator/state.json", "{}"),
                FileChange.delete("src/test/java/ATest.java"),
                FileChange.modify("pom.xml", "<dependency><artifactId>evil-lib</artifactId></dependency>")), Map.of()));
        assertThat(v).anyMatch(x -> x.policyId().equals("security.forbidden-apis") && x.severity() == Severity.HIGH);
        assertThat(v).anyMatch(x -> x.policyId().equals("security.secrets") && x.severity() == Severity.CRITICAL);
        assertThat(v).anyMatch(x -> x.policyId().equals("change-control") && x.severity() == Severity.CRITICAL);
        assertThat(v).anyMatch(x -> x.message().contains("protected path"));
        assertThat(v).anyMatch(x -> x.message().contains("deleting tests"));
        assertThat(v).anyMatch(x -> x.message().contains("evil-lib"));
        assertThat(pe.evaluate(new PolicyInput("n", List.of(FileChange.create("src/main/java/B.java", "class B {}")), Map.of())))
                .isEmpty();
    }

    @Test
    void riskClassifierFlagsHighImpactActions() {
        var findings = new RiskClassifier().classify(List.of(
                FileChange.modify("pom.xml", "x"), FileChange.delete("src/main/java/Old.java"),
                FileChange.modify("src/test/java/ATest.java", "x"), FileChange.create("src/main/java/New.java", "x")));
        assertThat(findings).hasSize(3);
    }

    @Test
    void approvalManagerResolvesOnceAndAudits(@TempDir Path dir) throws Exception {
        var audit = new AuditLogger(dir.resolve("a.jsonl"), "r", Clock.systemUTC());
        var m = new HumanApprovalManager("r", audit, Clock.systemUTC());
        var req = m.request("architecture", ApprovalKind.GATE_1, "review the plan");
        assertThat(m.pending()).hasSize(1);
        m.resolve(req.id(), Decision.REJECT, "more detail", Channel.REST, "alice");
        assertThat(m.pending()).isEmpty();
        assertThat(m.drainResolved()).hasSize(1);
        assertThat(m.drainResolved()).isEmpty();
        assertThatThrownBy(() -> m.resolve(req.id(), Decision.APPROVE, null, Channel.REST, "bob"))
                .isInstanceOf(IllegalStateException.class);
        var events = AuditLogger.readAll(dir.resolve("a.jsonl"));
        assertThat(events).hasSize(1);
        assertThat(events.get(0).type()).isEqualTo(AuditEventType.HUMAN_APPROVAL);
        assertThat(events.get(0).data()).containsEntry("decision", "REJECT").containsEntry("channel", "REST")
                .containsEntry("actor", "alice").containsEntry("feedback", "more detail");
    }
}
