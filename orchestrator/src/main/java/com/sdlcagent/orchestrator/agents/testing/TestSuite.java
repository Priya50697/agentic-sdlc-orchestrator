package com.sdlcagent.orchestrator.agents.testing;

import com.sdlcagent.orchestrator.agents.base.FileEntry;
import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.List;
import java.util.Map;

/**
 * The Test Engineer's LLM output. {@code files} are whole new/replaced test files; {@code edits} are exact-match
 * text replacements applied by the orchestrator to existing test files (the way to change a baseline test without
 * re-emitting the whole file); {@code supersededTests} declares every baseline test that is intentionally changed
 * because the new behaviour contradicts it, with the reason. Results are measured afterwards by running the tests.
 */
public record TestSuite(String summary, List<FileEntry> files, List<TestEdit> edits,
                        List<SupersededTest> supersededTests) {
    public record TestEdit(String path, String find, String replace) {}

    /**
     * Tolerant on input: the model does not always use the documented {@code testId} key. Accepts a bare
     * "Class#method" string, {@code testId|test|id|name} keys, or {@code testClass}+{@code testMethod}.
     */
    public record SupersededTest(String testId, String reason) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static SupersededTest lenient(Object raw) {
            if (raw instanceof String s) return new SupersededTest(s.strip(), null);
            if (raw instanceof Map<?, ?> m) {
                String id = firstText(m, "testId", "test", "id", "name", "testName");
                if (id == null) {
                    String cls = firstText(m, "testClass", "class", "className");
                    String method = firstText(m, "testMethod", "method", "methodName");
                    if (cls != null && method != null) id = cls + "#" + method;
                }
                return new SupersededTest(id, firstText(m, "reason", "why", "justification"));
            }
            return new SupersededTest(null, null);
        }

        private static String firstText(Map<?, ?> m, String... keys) {
            for (String k : keys) {
                if (m.get(k) instanceof String s && !s.isBlank()) return s.strip();
            }
            return null;
        }
    }

    public TestSuite {
        files = files == null ? List.of() : files;
        edits = edits == null ? List.of() : edits;
        supersededTests = supersededTests == null ? List.of() : supersededTests;
    }

    public TestSuite(String summary, List<FileEntry> files) {
        this(summary, files, List.of(), List.of());
    }
}
