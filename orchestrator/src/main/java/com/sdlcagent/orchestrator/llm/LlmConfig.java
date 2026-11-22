package com.sdlcagent.orchestrator.llm;

import com.sdlcagent.orchestrator.persistence.RunPaths;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class LlmConfig {

    @Bean
    public RunPaths runPaths(LlmProperties props) {
        return new RunPaths(props.stateRoot());
    }

    @Bean
    @Profile("!anthropic")
    @Qualifier("raw")
    public LlmClient rawLlmClient(LlmProperties p) {
        return new ClaudeCliAdapter(p.cliExecutable(), p.model(), p.timeout());
    }

    @Bean
    @Profile("anthropic")
    @Qualifier("raw")
    public LlmClient rawAnthropicClient(ChatModel chatModel, LlmProperties p) {
        return new SpringAiAnthropicClient(ChatClient.builder(chatModel).build(), p.model());
    }

    /** Everything that injects LlmClient gets the caching decorator. */
    @Bean
    @Primary
    public LlmClient cachingLlmClient(@Qualifier("raw") LlmClient raw, RunPaths paths, LlmProperties p) {
        return new CachingLlmClient(raw, paths.llmCacheDir(), p.cacheMode(), p.model());
    }
}
