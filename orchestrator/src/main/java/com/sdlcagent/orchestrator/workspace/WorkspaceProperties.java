package com.sdlcagent.orchestrator.workspace;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** orchestrator.workspace.* — where the generated app lives and how long Maven may run. */
@ConfigurationProperties(prefix = "orchestrator.workspace")
public record WorkspaceProperties(Path dir, Duration mavenTimeout, String projectPackage) {
    public WorkspaceProperties {
        dir = dir == null ? Path.of("../url-shortener") : dir;
        mavenTimeout = mavenTimeout == null ? Duration.ofMinutes(5) : mavenTimeout;
        projectPackage = projectPackage == null ? "com.sdlcagent.urlshortener" : projectPackage;
    }
}
