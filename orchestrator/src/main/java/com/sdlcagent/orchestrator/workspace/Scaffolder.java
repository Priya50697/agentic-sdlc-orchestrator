package com.sdlcagent.orchestrator.workspace;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes the greenfield baseline (pom, Maven wrapper, Application, application.yml) into the workspace and makes
 * the initial checkpoint. Idempotent: an existing pom.xml means "already scaffolded".
 */
public class Scaffolder {
    /** classpath resource (under /scaffold/) -> workspace-relative target. */
    private static final Map<String, String> FILES = new LinkedHashMap<>();

    static {
        FILES.put("pom.xml", "pom.xml");
        FILES.put("mvnw", "mvnw");
        FILES.put("mvnw.cmd", "mvnw.cmd");
        FILES.put("dot-mvn/wrapper/maven-wrapper.properties", ".mvn/wrapper/maven-wrapper.properties");
        FILES.put("app/Application.java.txt", "src/main/java/com/sdlcagent/urlshortener/Application.java");
        FILES.put("app/application.yml", "src/main/resources/application.yml");
    }

    private final Path workspace;
    private final WorkspaceGit git;

    public Scaffolder(Path workspace, WorkspaceGit git) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.git = git;
    }

    /** @return the checkpoint commit of the baseline, or the current head if the workspace was already scaffolded */
    public String scaffold() {
        git.init();
        if (Files.exists(workspace.resolve("pom.xml"))) return git.head();
        try {
            for (Map.Entry<String, String> e : FILES.entrySet()) {
                Path target = workspace.resolve(e.getValue());
                Files.createDirectories(target.getParent());
                try (InputStream in = Scaffolder.class.getResourceAsStream("/scaffold/" + e.getKey())) {
                    if (in == null) throw new IllegalStateException("missing scaffold resource " + e.getKey());
                    Files.write(target, in.readAllBytes());
                }
            }
            Files.createDirectories(workspace.resolve("src/test/java/com/sdlcagent/urlshortener"));
            Files.writeString(workspace.resolve("src/test/java/com/sdlcagent/urlshortener/.gitkeep"), "");
            workspace.resolve("mvnw").toFile().setExecutable(true);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return git.checkpoint("scaffold: baseline");
    }
}
