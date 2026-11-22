package com.sdlcagent.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.sdlcagent.orchestrator.llm.CachingLlmClient;
import com.sdlcagent.orchestrator.llm.LlmClient;
import com.sdlcagent.orchestrator.llm.SpringAiAnthropicClient;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Proves the "anthropic" profile wires the Spring AI client (dummy key, no network call is made). */
@SpringBootTest(properties = {"ANTHROPIC_API_KEY=dummy", "orchestrator.llm.state-root=target/test-state-anthropic"})
@ActiveProfiles("anthropic")
class AnthropicProfileWiringTest {
    @Autowired
    LlmClient primary;
    @Autowired
    @Qualifier("raw")
    LlmClient raw;

    @Test
    void anthropicProfileUsesSpringAiClientBehindCache() {
        assertThat(primary).isInstanceOf(CachingLlmClient.class);
        assertThat(raw).isInstanceOf(SpringAiAnthropicClient.class);
    }
}
