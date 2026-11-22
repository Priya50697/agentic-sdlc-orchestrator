package com.sdlcagent.orchestrator.agents.implementation;

import com.sdlcagent.orchestrator.agents.base.FileEntry;
import java.util.List;

/** The Implementer's output for one task: the complete files to write. Nothing is applied until policies pass. */
public record CodeChangeSet(String summary, List<FileEntry> files) {
    public CodeChangeSet {
        files = files == null ? List.of() : files;
    }
}
