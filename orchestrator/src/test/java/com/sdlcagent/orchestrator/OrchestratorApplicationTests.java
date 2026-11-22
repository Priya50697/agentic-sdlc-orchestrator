package com.sdlcagent.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.sdlcagent.orchestrator.llm.CachingLlmClient;
import com.sdlcagent.orchestrator.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "orchestrator.llm.state-root=target/test-state")
class OrchestratorApplicationTests {
    @Autowired
    LlmClient llm;

    @Test
    void defaultProfileWiresCachingDecoratorAndStartsWithoutApiKey() {
        assertThat(llm).isInstanceOf(CachingLlmClient.class);
    }
}
