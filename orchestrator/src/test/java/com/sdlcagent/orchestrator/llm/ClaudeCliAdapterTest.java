package com.sdlcagent.orchestrator.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

class ClaudeCliAdapterTest {
    // Trimmed from a real `claude -p --output-format json` reply.
    private static final String ENVELOPE = """
            {"total_cost_usd":0.0001364,"usage":{"input_tokens":2,"cache_creation_input_tokens":671,
            "cache_read_input_tokens":0,"output_tokens":4},
            "modelUsage":{"claude-haiku-5-5":{"inputTokens":2}},
            "is_error":false,"result":"OK","duration_ms":738,"type":"result"}
            """;

    @Test
    void parsesRealEnvelope() {
        LlmResponse r = ClaudeCliAdapter.parseEnvelope(ENVELOPE, "haiku", 5);
        assertThat(r.text()).isEqualTo("OK");
        assertThat(r.model()).isEqualTo("claude-haiku-5-5");
        assertThat(r.inputTokens()).isEqualTo(673);
        assertThat(r.outputTokens()).isEqualTo(4);
        assertThat(r.costUsd()).isEqualTo(0.0001364);
        assertThat(r.durationMs()).isEqualTo(738);
    }

    @Test
    void rejectsErrorAndGarbageEnvelopes() {
        assertThatThrownBy(() -> ClaudeCliAdapter.parseEnvelope("{\"is_error\":true,\"result\":\"boom\"}", "m", 0))
                .isInstanceOfSatisfying(LlmException.class,
                        e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.PROCESS));
        assertThatThrownBy(() -> ClaudeCliAdapter.parseEnvelope("not json", "m", 0))
                .isInstanceOfSatisfying(LlmException.class,
                        e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.PROTOCOL));
    }

    @Test
    void commandDisablesToolsSettingsAndSessions() {
        var adapter = new ClaudeCliAdapter("claude", "haiku", Duration.ofSeconds(5));
        var cmd = adapter.buildCommand("haiku", "sys");
        assertThat(cmd).containsSubsequence("--tools", "", "--no-session-persistence", "--setting-sources", "");
        assertThat(cmd).contains("--strict-mcp-config", "--disable-slash-commands");
        assertThat(cmd).containsSubsequence("--model", "haiku", "--system-prompt", "sys");
    }

    /** Opt-in live smoke test: set CLAUDE_CLI_LIVE=1. Costs a fraction of a cent. */
    @Test
    @EnabledIfEnvironmentVariable(named = "CLAUDE_CLI_LIVE", matches = "1")
    void liveRoundTrip() {
        var adapter = new ClaudeCliAdapter("claude", "haiku", Duration.ofSeconds(90));
        LlmResponse r = adapter.complete(LlmRequest.of("You are terse.", "Reply with exactly: OK"));
        assertThat(r.text()).contains("OK");
    }
}
