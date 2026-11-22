package com.sdlcagent.orchestrator.agents;

import static org.assertj.core.api.Assertions.assertThat;

import com.sdlcagent.orchestrator.agents.base.AgentExecutor;
import com.sdlcagent.orchestrator.agents.base.PromptLoader;
import com.sdlcagent.orchestrator.agents.requirements.RequirementsAgent;
import com.sdlcagent.orchestrator.agents.requirements.RequirementsGate;
import com.sdlcagent.orchestrator.engine.FailureKind;
import com.sdlcagent.orchestrator.engine.NodeContext;
import com.sdlcagent.orchestrator.engine.NodeResult;
import com.sdlcagent.orchestrator.engine.StageGraph;
import com.sdlcagent.orchestrator.governance.ApprovalKind;
import com.sdlcagent.orchestrator.governance.GateContext;
import com.sdlcagent.orchestrator.governance.GateResult;
import com.sdlcagent.orchestrator.llm.FakeLlmClient;
import com.sdlcagent.orchestrator.llm.LlmException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RequirementsAgentTest {
    static final String GOOD = """
            ```json
            {"title":"Shortener","summary":"s",
             "stories":[{"id":"S1","text":"As a user I want short links"}],
             "acceptanceCriteria":[{"id":"AC-1","storyId":"S1","text":"POST /api/urls returns 201 with a code"}],
             "ambiguities":[],"nonFunctional":["H2"]}
            ```""";
    static final String BLOCKING = GOOD.replace("\"ambiguities\":[]",
            "\"ambiguities\":[{\"id\":\"A1\",\"question\":\"Expiry?\",\"severity\":\"BLOCKING\",\"proposedDefault\":\"none\"}]");

    private static NodeContext ctx(List<String> feedback) {
        return new NodeContext("r1", "requirements", "requirements", 1, Map.of(),
                Map.of(StageGraph.REQUIREMENT, "build a shortener"), feedback);
    }

    private static NodeResult run(FakeLlmClient llm, List<String> feedback) {
        return new AgentExecutor(new RequirementsAgent(llm, new PromptLoader())).execute(ctx(feedback));
    }

    @Test
    void happyPathProducesSpecArtifactAndUsage() {
        FakeLlmClient llm = FakeLlmClient.sequence(GOOD);
        NodeResult r = run(llm, List.of());
        assertThat(r.failure()).isNull();
        assertThat(r.artifacts()).containsKey(StageGraph.SPEC);
        assertThat(r.usage().llmCalls()).isEqualTo(1);
        assertThat(llm.requests().get(0).system()).contains("Requirements Analyst");
        assertThat(llm.requests().get(0).user()).contains("build a shortener");
    }

    @Test
    void feedbackIsPassedToTheModel() {
        FakeLlmClient llm = FakeLlmClient.sequence(GOOD);
        run(llm, List.of("expiry is 30 days"));
        assertThat(llm.requests().get(0).user()).contains("expiry is 30 days");
    }

    @Test
    void malformedReplyIsRepairedOnceWithTheParseError() {
        FakeLlmClient llm = FakeLlmClient.sequence("sorry, no json here", GOOD);
        NodeResult r = run(llm, List.of());
        assertThat(r.failure()).isNull();
        assertThat(r.usage().llmCalls()).isEqualTo(2);
        assertThat(llm.requests().get(1).user()).contains("PREVIOUS REPLY WAS REJECTED");
    }

    @Test
    void stillMalformedAfterRepairIsATransientFailureNotAnException() {
        NodeResult r = run(FakeLlmClient.sequence("nope", "still nope"), List.of());
        assertThat(r.failure().kind()).isEqualTo(FailureKind.TRANSIENT);
        assertThat(r.usage().llmCalls()).isEqualTo(2);
    }

    @Test
    void llmExceptionBecomesFailureInfo() {
        FakeLlmClient llm = new FakeLlmClient(req -> {
            throw new LlmException(LlmException.Kind.TIMEOUT, "slow");
        });
        NodeResult r = run(llm, List.of());
        assertThat(r.failure().kind()).isEqualTo(FailureKind.TRANSIENT);
        assertThat(r.failure().message()).contains("TIMEOUT");
    }

    @Test
    void gatePassesGoodSpec() {
        assertThat(gate(GOOD).status()).isEqualTo(GateResult.Status.PASS);
    }

    @Test
    void gateRaisesGate0ForBlockingAmbiguityWithProposedDefault() {
        GateResult g = gate(BLOCKING);
        assertThat(g.status()).isEqualTo(GateResult.Status.NEEDS_APPROVAL);
        assertThat(g.approvalKind()).isEqualTo(ApprovalKind.GATE_0);
        assertThat(g.approvalSummary()).contains("Expiry?").contains("proposed default: none");
    }

    @Test
    void gateRejectsSpecWithoutCriteriaOrWithVagueOnes() {
        String noAc = GOOD.replaceAll("\"acceptanceCriteria\":\\[.*?\\],\\s*\"ambiguities\"", "\"acceptanceCriteria\":[],\"ambiguities\"");
        assertThat(gate(noAc).status()).isEqualTo(GateResult.Status.FAIL);
        String vague = GOOD.replace("POST /api/urls returns 201 with a code", "works");
        assertThat(gate(vague).errors().toString()).contains("too vague");
        String badId = GOOD.replace("AC-1", "X1");
        assertThat(gate(badId).errors().toString()).contains("AC-1");
    }

    /** Runs the agent's output through the gate the way the engine would. */
    private static GateResult gate(String llmReply) {
        NodeResult r = run(FakeLlmClient.sequence(llmReply), List.of());
        if (r.failure() != null) {
            // agent-level validation could reject first; surface it as a failing gate for the assertions
            return GateResult.fail(r.failure());
        }
        return new RequirementsGate().evaluate(new GateContext("r1", "requirements", 1, Map.of(), r));
    }
}
