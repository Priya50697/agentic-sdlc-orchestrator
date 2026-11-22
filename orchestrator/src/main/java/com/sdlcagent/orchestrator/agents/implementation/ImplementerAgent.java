package com.sdlcagent.orchestrator.agents.implementation;

import com.sdlcagent.orchestrator.agents.base.AbstractLlmAgent;
import com.sdlcagent.orchestrator.agents.base.FileEntry;
import com.sdlcagent.orchestrator.agents.base.PromptLoader;
import com.sdlcagent.orchestrator.context.CodeIndexer;
import com.sdlcagent.orchestrator.engine.NodeContext;
import com.sdlcagent.orchestrator.engine.StageGraph;
import com.sdlcagent.orchestrator.governance.FileChange;
import com.sdlcagent.orchestrator.llm.LlmClient;
import com.sdlcagent.orchestrator.workspace.WorkspaceProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** One call per task node: writes only the files the task owns (its targetPaths). */
@Component
public class ImplementerAgent extends AbstractLlmAgent<CodeChangeSet> {
    private final CodeIndexer indexer;
    private final WorkspaceProperties workspace;

    public ImplementerAgent(LlmClient llm, PromptLoader prompts, CodeIndexer indexer, WorkspaceProperties workspace) {
        super(llm, prompts, CodeChangeSet.class);
        this.indexer = indexer;
        this.workspace = workspace;
    }

    @Override
    public String key() {
        return "implementer";
    }

    @Override
    protected String buildUserPrompt(NodeContext ctx) {
        List<String> targets = targets(ctx);
        String task = "id: " + ctx.params().get("taskId") + "\ndescription: " + ctx.params().get("description")
                + "\ntargetPaths: " + String.join(", ", targets)
                + "\nacceptanceCriteria: " + ctx.params().getOrDefault("acceptanceCriteria", "")
                + "\nproject package: " + workspace.projectPackage();
        return assemble(
                section("TASK", task),
                section("SPEC", input(ctx, StageGraph.SPEC)),
                section("ARCHITECTURE PLAN", input(ctx, StageGraph.PLAN)),
                section("EXISTING CODE (index of what is already in the workspace)", indexer.index(workspace.dir()).summary(20_000)),
                section("CURRENT CONTENT OF TARGET FILES", currentContent(targets)));
    }

    @Override
    protected String validate(NodeContext ctx, CodeChangeSet set) {
        String problem = FileEntry.firstProblem(set.files());
        if (problem != null) return problem;
        Set<String> allowed = Set.copyOf(targets(ctx));
        if (allowed.isEmpty()) return null;
        for (FileEntry f : set.files()) {
            String p = f.path().strip().replace('\\', '/');
            if (!allowed.contains(p)) {
                return "file " + p + " is not one of this task's targetPaths " + allowed + "; write only your own files";
            }
        }
        return null;
    }

    @Override
    protected List<FileChange> toChanges(CodeChangeSet set) {
        return FileEntry.toChanges(set.files());
    }

    @Override
    protected Map<String, String> toArtifacts(CodeChangeSet set) {
        return Map.of();
    }

    private static List<String> targets(NodeContext ctx) {
        String raw = ctx.params().getOrDefault("targetPaths", "");
        List<String> out = new ArrayList<>();
        for (String p : raw.split(",")) {
            if (!p.isBlank()) out.add(p.strip().replace('\\', '/'));
        }
        return out;
    }

    private String currentContent(List<String> targets) {
        return targets.stream().map(t -> {
            Path f = workspace.dir().resolve(t).normalize();
            try {
                return Files.exists(f) ? "--- " + t + " ---\n" + Files.readString(f, StandardCharsets.UTF_8)
                        : "--- " + t + " --- (does not exist yet)";
            } catch (IOException e) {
                return "--- " + t + " --- (unreadable)";
            }
        }).collect(Collectors.joining("\n"));
    }
}
