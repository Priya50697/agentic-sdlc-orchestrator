package com.sdlcagent.orchestrator.agents.docs;

import java.util.List;

/** Facts about the docs written: which files, which sections and endpoints they document, and whether a template was used. */
public record DocReport(String summary, List<String> files, List<String> sections, List<String> documentedEndpoints,
                        boolean templateFallback) {
    public DocReport {
        files = files == null ? List.of() : files;
        sections = sections == null ? List.of() : sections;
        documentedEndpoints = documentedEndpoints == null ? List.of() : documentedEndpoints;
    }
}
