package com.sdlcagent.orchestrator.workspace;

import com.sdlcagent.orchestrator.context.CodeIndexer;
import com.sdlcagent.orchestrator.persistence.RunPaths;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Beans only; nothing touches the disk until a run asks for it. */
@Configuration
@EnableConfigurationProperties(WorkspaceProperties.class)
public class WorkspaceConfig {

    @Bean
    public WorkspaceGit workspaceGit(RunPaths paths, WorkspaceProperties p) {
        return new WorkspaceGit(paths.workspaceGitDir(), p.dir());
    }

    @Bean
    public SandboxedFileWriter sandboxedFileWriter(WorkspaceProperties p) {
        return new SandboxedFileWriter(p.dir());
    }

    @Bean
    public MavenRunner mavenRunner(WorkspaceProperties p) {
        return new MavenRunner(p.dir(), p.mavenTimeout());
    }

    @Bean
    public Scaffolder scaffolder(WorkspaceProperties p, WorkspaceGit git) {
        return new Scaffolder(p.dir(), git);
    }

    @Bean
    public CodeIndexer codeIndexer() {
        return new CodeIndexer();
    }
}
