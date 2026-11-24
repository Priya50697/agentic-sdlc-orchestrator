package com.sdlcagent.orchestrator.agents.testing;

import com.sdlcagent.orchestrator.agents.base.FileEntry;
import java.util.List;

/**
 * The Test Engineer's LLM output. {@code files} are whole new/replaced test files; {@code edits} are exact-match
 * text replacements applied by the orchestrator to existing test files (the way to change a baseline test without
 * re-emitting the whole file); {@code supersededTests} declares every baseline test that is intentionally changed
 * because the new behaviour contradicts it, with the reason. Results are measured afterwards by running the tests.
 */
public record TestSuite(String summary, List<FileEntry> files, List<TestEdit> edits,
                        List<SupersededTest> supersededTests) {
    public record TestEdit(String path, String find, String replace) {}

    public record SupersededTest(String testId, String reason) {}

    public TestSuite {
        files = files == null ? List.of() : files;
        edits = edits == null ? List.of() : edits;
        supersededTests = supersededTests == null ? List.of() : supersededTests;
    }

    public TestSuite(String summary, List<FileEntry> files) {
        this(summary, files, List.of(), List.of());
    }
}
