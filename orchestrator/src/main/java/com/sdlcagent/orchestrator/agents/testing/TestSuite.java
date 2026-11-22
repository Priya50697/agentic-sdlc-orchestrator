package com.sdlcagent.orchestrator.agents.testing;

import com.sdlcagent.orchestrator.agents.base.FileEntry;
import java.util.List;

/** The Test Engineer's LLM output: test source files. Results are measured afterwards by running them. */
public record TestSuite(String summary, List<FileEntry> files) {
    public TestSuite {
        files = files == null ? List.of() : files;
    }
}
