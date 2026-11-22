package com.sdlcagent.orchestrator.llm;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** orchestrator.llm.* — cacheMode: record | replay | off. */
@ConfigurationProperties(prefix = "orchestrator.llm")
public record LlmProperties(String model, Duration timeout, String cliExecutable,
                            CachingLlmClient.Mode cacheMode, Path stateRoot) {
    public LlmProperties {
        model = model == null ? "sonnet" : model;
        timeout = timeout == null ? Duration.ofMinutes(5) : timeout;
        cliExecutable = cliExecutable == null ? "claude" : cliExecutable;
        cacheMode = cacheMode == null ? CachingLlmClient.Mode.RECORD : cacheMode;
        stateRoot = stateRoot == null ? Path.of(".orchestrator") : stateRoot;
    }
}
