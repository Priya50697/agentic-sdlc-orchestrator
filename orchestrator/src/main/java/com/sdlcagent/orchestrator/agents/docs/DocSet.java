package com.sdlcagent.orchestrator.agents.docs;

import com.sdlcagent.orchestrator.agents.base.FileEntry;
import java.util.List;

/** The DocWriter's LLM output: documentation files (README.md at minimum). */
public record DocSet(String summary, List<FileEntry> files) {
    public DocSet {
        files = files == null ? List.of() : files;
    }
}
