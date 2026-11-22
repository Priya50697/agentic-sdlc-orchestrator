package com.sdlcagent.orchestrator.agents.base;

import com.sdlcagent.orchestrator.context.ContextAssembler;
import com.sdlcagent.orchestrator.engine.FailureInfo;
import com.sdlcagent.orchestrator.engine.FailureKind;
import com.sdlcagent.orchestrator.engine.NodeContext;
import com.sdlcagent.orchestrator.engine.NodeResult;
import com.sdlcagent.orchestrator.governance.FileChange;
import com.sdlcagent.orchestrator.llm.JsonResponseParser;
import com.sdlcagent.orchestrator.llm.LlmClient;
import com.sdlcagent.orchestrator.llm.LlmException;
import com.sdlcagent.orchestrator.llm.LlmRequest;
import com.sdlcagent.orchestrator.llm.LlmResponse;
import java.util.List;
import java.util.Map;

/**
 * Template method: system prompt (resources/prompts/key.md) -> LlmClient -> JsonResponseParser -> typed record R.
 * A reply that does not parse or fails {@link #validate} gets exactly one repair attempt with the error as feedback;
 * after that the result is a TRANSIENT failure (the engine's retry budget takes over). Never throws for bad output.
 */
public abstract class AbstractLlmAgent<R> implements SdlcAgent {
    protected static final int CONTEXT_CHARS = 60_000;

    private final LlmClient llm;
    private final JsonResponseParser parser = new JsonResponseParser();
    private final String systemPrompt;
    private final Class<R> resultType;

    protected AbstractLlmAgent(LlmClient llm, PromptLoader prompts, Class<R> resultType) {
        this.llm = llm;
        this.resultType = resultType;
        this.systemPrompt = prompts.get(key());
    }

    /** User prompt body: the context pack for this node. Feedback is appended by the template. */
    protected abstract String buildUserPrompt(NodeContext ctx);

    protected abstract Map<String, String> toArtifacts(R result);

    protected List<FileChange> toChanges(R result) {
        return List.of();
    }

    /** Structural checks on the parsed record; null = fine, otherwise the problem (fed back to the LLM). */
    protected String validate(NodeContext ctx, R result) {
        return null;
    }

    /** Hook for agents that add deterministic facts after the LLM step (e.g. running tests). */
    protected NodeResult finish(NodeContext ctx, R result, NodeResult.Usage usage) {
        return NodeResult.ok(toArtifacts(result), toChanges(result), usage);
    }

    /** Hook: what to return when the LLM path failed for good. Default = the failure. */
    protected NodeResult onGiveUp(NodeContext ctx, NodeResult failed) {
        return failed;
    }

    @Override
    public final NodeResult run(NodeContext ctx) {
        String base = buildUserPrompt(ctx);
        if (!ctx.feedback().isEmpty()) {
            base += "\n\n=== FEEDBACK TO ADDRESS (from the previous attempt / reviewer) ===\n"
                    + String.join("\n---\n", ctx.feedback());
        }
        NodeResult.Usage usage = NodeResult.Usage.NONE;
        String problem = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            String user = problem == null ? base : base + "\n\n=== YOUR PREVIOUS REPLY WAS REJECTED ===\n" + problem
                    + "\nReturn only the corrected JSON object.";
            LlmResponse resp;
            try {
                resp = llm.complete(LlmRequest.of(systemPrompt, user));
            } catch (LlmException e) {
                return onGiveUp(ctx, fail(usage, key() + ": LLM " + e.kind() + " " + e.getMessage()));
            }
            usage = add(usage, resp);
            R parsed;
            try {
                parsed = parser.parse(resp.text(), resultType);
            } catch (LlmException e) {
                problem = e.getMessage();
                continue;
            }
            if (parsed == null) {
                problem = "reply was empty JSON";
                continue;
            }
            problem = validate(ctx, parsed);
            if (problem == null) return finish(ctx, parsed, usage);
        }
        return onGiveUp(ctx, fail(usage, key() + ": invalid output after repair attempt: " + problem));
    }

    private static NodeResult fail(NodeResult.Usage usage, String msg) {
        return new NodeResult(Map.of(), List.of(), usage, FailureInfo.of(FailureKind.TRANSIENT, msg));
    }

    private static NodeResult.Usage add(NodeResult.Usage u, LlmResponse r) {
        boolean hit = (u.llmCalls() == 0 || u.cacheHit()) && r.cacheHit();
        return new NodeResult.Usage(u.llmCalls() + 1, u.inputTokens() + r.inputTokens(),
                u.outputTokens() + r.outputTokens(), u.costUsd() + r.costUsd(), hit);
    }

    // ---- context helpers

    protected static String input(NodeContext ctx, String artifactId) {
        String v = ctx.inputs().get(artifactId);
        return v == null ? "(not available)" : v;
    }

    protected static String assemble(ContextAssembler.Section... sections) {
        return new ContextAssembler(CONTEXT_CHARS).assemble(List.of(sections));
    }

    protected static ContextAssembler.Section section(String title, String content) {
        return new ContextAssembler.Section(title, content);
    }
}
