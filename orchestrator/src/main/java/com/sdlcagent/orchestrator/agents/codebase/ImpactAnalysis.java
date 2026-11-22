package com.sdlcagent.orchestrator.agents.codebase;

import java.util.List;

/** What an existing codebase must change for the spec. Paths and classes must exist in the CodeIndex (ImpactGate). */
public record ImpactAnalysis(String summary, List<String> affectedFiles, List<String> affectedClasses,
                             List<String> newComponents, List<String> risks) {
    public ImpactAnalysis {
        affectedFiles = affectedFiles == null ? List.of() : affectedFiles;
        affectedClasses = affectedClasses == null ? List.of() : affectedClasses;
        newComponents = newComponents == null ? List.of() : newComponents;
        risks = risks == null ? List.of() : risks;
    }
}
