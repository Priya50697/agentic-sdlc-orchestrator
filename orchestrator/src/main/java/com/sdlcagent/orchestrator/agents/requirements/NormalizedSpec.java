package com.sdlcagent.orchestrator.agents.requirements;

import java.util.List;

/** The normalized requirement: stories, numbered testable acceptance criteria, and ambiguities with proposed defaults. */
public record NormalizedSpec(String title, String summary, List<Story> stories, List<Criterion> acceptanceCriteria,
                             List<Ambiguity> ambiguities, List<String> nonFunctional) {

    public record Story(String id, String text) {}

    /** id is "AC-n"; storyId links back to a story. */
    public record Criterion(String id, String storyId, String text) {}

    /** severity is BLOCKING or MINOR; the gate (not the LLM) decides what blocking means for the run. */
    public record Ambiguity(String id, String question, String severity, String proposedDefault) {
        public boolean blocking() {
            return "BLOCKING".equalsIgnoreCase(severity);
        }
    }

    public NormalizedSpec {
        stories = stories == null ? List.of() : stories;
        acceptanceCriteria = acceptanceCriteria == null ? List.of() : acceptanceCriteria;
        ambiguities = ambiguities == null ? List.of() : ambiguities;
        nonFunctional = nonFunctional == null ? List.of() : nonFunctional;
    }
}
